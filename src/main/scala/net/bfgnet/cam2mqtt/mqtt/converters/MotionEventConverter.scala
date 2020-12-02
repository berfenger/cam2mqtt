package net.bfgnet.cam2mqtt.mqtt.converters

import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraMotionEvent

trait MotionEventConverter extends CameraEventConverter {

    implicit def convertCameraEventConverter(event: CameraMotionEvent): MqttMessage = {
        val value = if (event.motion) "on" else "off"
        MqttMessage(s"${cameraEventModulePath(event.cameraId, event.moduleId)}/motion", ByteString(value))
    }
}
