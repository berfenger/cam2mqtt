package net.bfgnet.cam2mqtt.mqtt

import net.bfgnet.cam2mqtt.camera.CameraProtocol._

class MqttCamera(base: String) {

    import net.bfgnet.cam2mqtt.mqtt.converters.CameraEventConverters._

    def cameraEventToMqttMessage(ev: CameraAvailableEvent) = {
        implicit val a = MqttBaseTopicProvider("cam2mqtt")
        ev.toMqtt()
    }

    def cameraEventToMqttMessage2(ev: CameraMotionEvent) = {
        ev.toMqtt("asd")
    }



    private def cameraBasePath(camId: String) =
        s"$base/camera/$camId"

    private def cameraEventBasePath(ev: CameraEvent) =
        s"${cameraBasePath(ev.cameraId)}/event/module/${ev.moduleId}"

    // camera event to path
    /*private def cameraEventPath(ev: CameraEvent): Option[String] = {
        ev match {
            case ev: CameraAvailableEvent =>
                Option(s"${cameraBasePath(ev.cameraId)}/available")
            case ev =>
                cameraEventName(ev) match {
                    case Some(evName) => Option(cameraEventBasePath(ev) + evName)
                    case None => None
                }
        }
    }*/

    // module camera event to path
    private def cameraEventName: PartialFunction[CameraEvent, String] = {
        case _: CameraMotionEvent => "motion"
    }

    // camera event to string value
    private def cameraEventValue: PartialFunction[CameraEvent, String] = {
        case ev: CameraAvailableEvent =>
            if (ev.available) "online" else "offline"
        case ev: CameraMotionEvent =>
            if (ev.motion) "on" else "off"
    }

}
