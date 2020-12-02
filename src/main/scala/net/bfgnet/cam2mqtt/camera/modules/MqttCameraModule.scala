package net.bfgnet.cam2mqtt.camera.modules

import akka.stream.alpakka.mqtt.MqttMessage
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol.CameraActionRequest
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraEvent
import net.bfgnet.cam2mqtt.mqtt.path.MqttPaths

trait MqttCameraModule extends MqttPaths {

    val moduleId: String

    def parseMQTTCommand(path: List[String], stringData: String): Option[CameraActionRequest]

    def eventToMqttMessage(ev: CameraEvent): Option[MqttMessage]
}
