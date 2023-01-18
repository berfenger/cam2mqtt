package net.bfgnet.cam2mqtt
package camera.modules.onvif

import camera.CameraActionProtocol
import camera.CameraConfig.{CameraInfo, CameraModuleConfig, OnvifCameraModuleConfig}
import camera.CameraProtocol._
import camera.modules.{CameraModule, MqttCameraModule}
import onvif.OnvifGetPropertiesRequests.OnvifCapabilitiesResponse
import onvif.OnvifRequests
import utils.ActorContextImplicits

import akka.actor.typed.scaladsl.ActorContext
import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}

trait OnvifModuleUtils extends CameraModule with MqttCameraModule with ActorContextImplicits {

    protected def getOnvifCapabilities(camera: CameraInfo, config: OnvifCameraModuleConfig)(implicit _ac: ActorContext[CameraCmd]) = {
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
        val debounceMotion = from.getBool("motion_debounce").orTrue
        val forceDebounceTime = from.getDuration("force_motion_debounce_time").map(toFiniteDuration).filter(_.toMillis > 0)
        OnvifCameraModuleConfig(port, monitorEvs.orFalse, preferWebhook.orFalse, debounceMotion, forceDebounceTime)
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

    protected def logCapabilities(cameraId: String, caps: OnvifCapabilitiesResponse)(implicit _ac: ActorContext[_]) = {
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

    protected def toFiniteDuration(d: Duration): FiniteDuration =
        FiniteDuration(d.toMillis, TimeUnit.MILLISECONDS)

}
