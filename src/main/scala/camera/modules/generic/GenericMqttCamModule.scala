package net.bfgnet.cam2mqtt
package camera.modules.generic

import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import camera.CameraProtocol.CameraAvailableEvent
import camera.{CameraActionProtocol, CameraProtocol}
import camera.modules.MqttCameraModule

object GenericMqttCamModule extends MqttCameraModule {
    override val moduleId: String = "generic"

    override def parseMQTTCommand(path: List[String], stringData: String): Option[CameraActionProtocol.CameraActionRequest] = None

    override def eventToMqttMessage(ev: CameraProtocol.CameraEvent): Option[MqttMessage] = ev match {
        case CameraAvailableEvent(cameraId, available) =>
            val value = if (available) "online" else "offline"
            Some(MqttMessage(s"${cameraPath(cameraId)}/status", ByteString(value)).withRetained(true))
        case _ => None
    }
}
