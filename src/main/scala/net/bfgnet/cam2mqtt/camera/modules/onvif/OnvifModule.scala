package net.bfgnet.cam2mqtt.camera.modules.onvif

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Terminated}
import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol
import net.bfgnet.cam2mqtt.camera.CameraConfig.{CameraInfo, CameraModuleConfig, OnvifCameraModuleConfig}
import net.bfgnet.cam2mqtt.camera.CameraProtocol._
import net.bfgnet.cam2mqtt.camera.modules.{CameraModule, MqttCameraModule}
import net.bfgnet.cam2mqtt.camera.modules.onvif.OnvifSubProtocol.{OnvifSubCmd, TerminateSubscription, WebhookNotification}
import net.bfgnet.cam2mqtt.config.ConfigManager
import net.bfgnet.cam2mqtt.onvif.OnvifGetPropertiesRequests.OnvifCapabilitiesResponse
import net.bfgnet.cam2mqtt.onvif.OnvifRequests
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits

import scala.util.{Failure, Success}

sealed trait OnvifModuleCmd

case class OnvifCapabilities(resp: OnvifCapabilitiesResponse) extends OnvifModuleCmd

case class OnvifError(error: Throwable) extends OnvifModuleCmd

object OnvifModule extends CameraModule with MqttCameraModule with ActorContextImplicits {
    override val moduleId: String = "onvif"

    override def createBehavior(camera: ActorRef[CameraCmd], info: CameraInfo, config: CameraModuleConfig): Behavior[CameraCmd] =
        stopped(camera, info, Option(config).filter(_.isInstanceOf[OnvifCameraModuleConfig]).map(_.asInstanceOf[OnvifCameraModuleConfig]))

    private def stopped(parent: ActorRef[CameraCmd], camera: CameraInfo,
                        config: Option[OnvifCameraModuleConfig]): Behavior[CameraCmd] = {
        Behaviors.setup[CameraCmd] { implicit context =>

            getOnvifCapabilities(camera)

            Behaviors.receiveMessagePartial[CameraCmd] {
                case WrappedModuleCmd(oc@OnvifCapabilities(cap)) =>
                    // start subscription
                    val subBehav = if (cap.hasEvents && config.exists(_.monitorEvents)) {
                        // webhook
                        val a = if (config.exists(_.preferWebhookSub) || !cap.hasPullPointSupport) {
                            OnvifWebhookSub.apply(context.self, ConfigManager.webhookSubscription, camera)
                        } else { // pull point
                            OnvifPullPointSub.apply(context.self, camera)
                        }
                        Some(a)
                    } else None
                    val subActor = subBehav.map(context.spawn(_, "subscription")).map { a =>
                        // watch child actor
                        context.watch(a)
                        a
                    }
                    started(parent, subActor)
                case WrappedModuleCmd(OnvifError(err)) =>
                    throw err
                case TerminateCam =>
                    Behaviors.stopped
            }
        }
    }

    private def started(parent: ActorRef[CameraCmd],
                        subscriptionActor: Option[ActorRef[OnvifSubCmd]]): Behavior[CameraCmd] = {
        Behaviors.setup[CameraCmd] { implicit context =>

            Behaviors.receiveMessagePartial[CameraCmd] {
                // webhook notification
                case CameraModuleMessage(_, _, msg) =>
                    subscriptionActor.foreach(_ ! WebhookNotification(msg))
                    Behaviors.same
                // Event from subscription
                case ev: CameraModuleEvent =>
                    parent ! ev
                    Behaviors.same
                case WrappedModuleCmd(OnvifError(err)) =>
                    throw err
                case TerminateCam =>
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

    private def getOnvifCapabilities(camera: CameraInfo)(implicit _ac: ActorContext[CameraCmd]) = {
        _ac.pipeToSelf(OnvifRequests.getCapabilities(camera.host, camera.port, camera.username, camera.password)) {
            case Success(r) => WrappedModuleCmd(OnvifCapabilities(r))
            case Failure(ex) => WrappedModuleCmd(OnvifError(ex))
        }
    }

    override def loadConfiguration(from: Map[String, Any]): CameraModuleConfig = {
        val monitorEvs = from.get("monitor_events").filter(_ != null).map(_.toString).contains("true")
        val preferWebhook = from.get("prefer_webhook_subscription").filter(_ != null).map(_.toString).contains("true")
        OnvifCameraModuleConfig(monitorEvs, preferWebhook)
    }

    override def parseMQTTCommand(path: List[String], stringData: String): Option[CameraActionProtocol.CameraActionRequest] = None

    override def eventToMqttMessage(ev: CameraEvent): Option[MqttMessage] = ev match {
        case CameraMotionEvent(cameraId, moduleId, motion) =>
            val value = if (motion) "on" else "off"
            Some(MqttMessage(s"${cameraEventModulePath(cameraId, moduleId)}/motion", ByteString(value)))
        case _ => None
    }
}
