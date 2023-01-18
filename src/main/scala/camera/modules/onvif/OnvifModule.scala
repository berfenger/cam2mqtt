package net.bfgnet.cam2mqtt
package camera.modules.onvif

import camera.CameraConfig.{CameraInfo, CameraModuleConfig, OnvifCameraModuleConfig}
import camera.CameraProtocol._
import camera.modules.onvif.subscription.OnvifSubProtocol.{OnvifSubCmd, TerminateSubscription, WebhookNotification}
import camera.modules.onvif.subscription.{OnvifPullPointSub, OnvifWebhookSub}
import camera.modules.{CameraModule, MqttCameraModule}
import config.ConfigManager
import eventbus.CameraEventBus
import onvif.OnvifGetPropertiesRequests.OnvifCapabilitiesResponse
import utils.ActorContextImplicits

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, Terminated}

sealed trait OnvifModuleCmd

case class OnvifCapabilities(resp: OnvifCapabilitiesResponse) extends OnvifModuleCmd

case class OnvifError(error: Throwable) extends OnvifModuleCmd

object OnvifModule extends CameraModule with OnvifModuleUtils with MqttCameraModule with ActorContextImplicits {
    override val moduleId: String = "onvif"

    override def createBehavior(camera: ActorRef[CameraCmd], info: CameraInfo, config: CameraModuleConfig): Behavior[CameraCmd] =
        stopped(camera, info, config.asInstanceOf[OnvifCameraModuleConfig])

    private def stopped(parent: ActorRef[CameraCmd], camera: CameraInfo,
                        config: OnvifCameraModuleConfig): Behavior[CameraCmd] = {
        Behaviors.setup[CameraCmd] { implicit context =>
            getOnvifCapabilities(camera, config)
            Behaviors.receiveMessagePartial[CameraCmd] {
                case WrappedModuleCmd(oc@OnvifCapabilities(cap)) =>
                    logCapabilities(camera.cameraId, cap)
                    // start subscription
                    val subBehav = if (cap.hasEvents && config.monitorEvents) {
                        // webhook
                        val a = if (config.preferWebhookSub || !cap.hasPullPointSupport) {
                            OnvifWebhookSub.apply(context.self, ConfigManager.webhookSubscription, camera, config)
                        } else { // pull point
                            OnvifPullPointSub.apply(context.self, camera, config)
                        }
                        Some(a)
                    } else None
                    val subActor = subBehav.map(context.spawn(_, "subscription")).map { a =>
                        // watch child actor
                        context.watch(a)
                        a
                    }
                    started(parent, camera, config, subActor, motion = false, None)
                case WrappedModuleCmd(OnvifError(err)) =>
                    throw err
                case TerminateCam =>
                    Behaviors.stopped
            }
        }
    }

    private def started(parent: ActorRef[CameraCmd], camera: CameraInfo, config: OnvifCameraModuleConfig,
                        subscriptionActor: Option[ActorRef[OnvifSubCmd]], motion: Boolean): Behavior[CameraCmd] = {
        Behaviors.receiveMessagePartial[CameraCmd] {
            // webhook notification
            case CameraModuleMessage(_, _, msg) =>
                subscriptionActor.foreach(_ ! WebhookNotification(msg))
                Behaviors.same
            // Event from subscription
            case ev@CameraModuleEvent(_, _, CameraMotionEvent(_, _, evMotion)) =>
                val updateMotion = (motion != evMotion) || !config.debounceMotion
                if (updateMotion) {
                    parent ! ev
                    started(parent, camera, config, subscriptionActor, evMotion)
                } else {
                    Behaviors.same
                }
            case ev: CameraModuleEvent =>
                parent ! ev
                Behaviors.same
            case WrappedModuleCmd(OnvifError(err)) =>
                throw err
            case TerminateCam =>
                // if motion, publish default state
                if (motion) {
                    CameraEventBus.bus.publish(CameraMotionEvent(camera.cameraId, OnvifModule.moduleId, motion = false))
                }
                subscriptionActor match {
                    case Some(s) =>
                        s ! TerminateSubscription
                        finishing(List(s))
                    case None =>
                        Behaviors.stopped
                }
        }.receiveSignal {
            case (_, Terminated(actor)) =>
                throw new Exception(s"subscription actor [$actor] failed")
            case (_, PostStop) =>
                Behaviors.same
        }
    }

    private def finishing(children: List[ActorRef[_]]): Behavior[CameraCmd] =
        Behaviors.receiveSignal {
            case (_, Terminated(a)) =>
                val remaining = children.filterNot(_ == a)
                if (remaining.nonEmpty)
                    finishing(remaining)
                else {
                    Behaviors.stopped
                }
        }
}
