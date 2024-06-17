package net.bfgnet.cam2mqtt
package camera.modules

import org.apache.pekko.stream.connectors.mqtt.MqttMessage
import camera.CameraActionProtocol.CameraActionRequest
import camera.CameraProtocol.CameraEvent
import mqtt.path.MqttPaths

trait MqttCameraModule extends MqttPaths {

    val moduleId: String

    def parseMQTTCommand(path: List[String], stringData: String): Option[CameraActionRequest]

    def eventToMqttMessage(ev: CameraEvent): Option[MqttMessage]
}
