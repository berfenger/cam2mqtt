package net.bfgnet.cam2mqtt
package camera.modules.onvif

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Terminated}
import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import camera.CameraActionProtocol
import camera.CameraConfig.{CameraInfo, CameraModuleConfig, OnvifCameraModuleConfig}
import camera.CameraProtocol._
import camera.modules.{CameraModule, MqttCameraModule}
import camera.modules.onvif.OnvifSubProtocol.{OnvifSubCmd, TerminateSubscription, WebhookNotification}
import config.ConfigManager
import onvif.OnvifGetPropertiesRequests.OnvifCapabilitiesResponse
import onvif.OnvifRequests
import utils.ActorContextImplicits

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

sealed trait OnvifModuleCmd

case class OnvifCapabilities(resp: OnvifCapabilitiesResponse) extends OnvifModuleCmd

case class OnvifError(error: Throwable) extends OnvifModuleCmd

object OnvifModule extends CameraModule with MqttCameraModule with ActorContextImplicits {
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

    private def getOnvifCapabilities(camera: CameraInfo, config: OnvifCameraModuleConfig)(implicit _ac: ActorContext[CameraCmd]) = {
        _ac.pipeToSelf(OnvifRequests.getCapabilities(camera.host, config.port, camera.username, camera.password)) {
            case Success(r) => WrappedModuleCmd(OnvifCapabilities(r))
            case Failure(ex) => WrappedModuleCmd(OnvifError(ex))
        }
    }

    override def loadConfiguration(from: Map[String, Any]): CameraModuleConfig = {
        import utils.ConfigParserUtils._
        val port = from.getInt("port").getOrElse(8000)
        val monitorEvs = from.getBool("monitor_events")
        val preferWebhook = from.getBool("prefer_webhook_subscription")
        OnvifCameraModuleConfig(port, monitorEvs.orFalse, preferWebhook.orFalse)
    }

    override def parseMQTTCommand(path: List[String], stringData: String): Option[CameraActionProtocol.CameraActionRequest] = None

    override def eventToMqttMessage(ev: CameraEvent): Option[MqttMessage] = ev match {
        case CameraMotionEvent(cameraId, moduleId, motion) =>
            val value = if (motion) "on" else "off"
            Some(MqttMessage(s"${cameraEventModulePath(cameraId, moduleId)}/motion", ByteString(value)))
        case CameraObjectDetectionEvent(cameraId, moduleId, objectClass, detection) =>
            val value = if (detection) "on" else "off"
            Some(MqttMessage(s"${cameraEventModulePath(cameraId, moduleId)}/object/$objectClass/detected", ByteString(value)))
        case CameraVisitorEvent(cameraId, moduleId, detection) =>
            val value = if (detection) "on" else "off"
            Some(MqttMessage(s"${cameraEventModulePath(cameraId, moduleId)}/visitor", ByteString(value)))
        case _ => None
    }

    private def logCapabilities(cameraId: String, caps: OnvifCapabilitiesResponse)(implicit _ac: ActorContext[_]) = {
        val c = new mutable.StringBuilder()
        c ++= s"ONVIF Capabilities for camera $cameraId:\n"
        if (caps.hasEvents) {
            c ++= s"Events: ${caps.hasEvents}\n"
        }
        if (caps.hasPullPointSupport) {
            c ++= s"Pull-Point subscription: ${caps.hasPullPointSupport}\n"
        }
        if (caps.hasPTZ) {
            c ++= s"PTZ: ${caps.hasPTZ}\n"
        }
        _ac.log.info(c.toString().trim)
    }
}
