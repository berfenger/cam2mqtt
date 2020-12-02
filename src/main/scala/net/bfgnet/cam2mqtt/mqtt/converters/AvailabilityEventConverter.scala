package net.bfgnet.cam2mqtt.mqtt.converters

import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraAvailableEvent

trait AvailabilityEventConverter extends CameraEventConverter {

    implicit def convertCameraEventConverter(event: CameraAvailableEvent): MqttMessage = {
        val value = if (event.available) "online" else "offline"
        MqttMessage(s"${cameraPath(event.cameraId)}/status", ByteString(value))
    }
}
