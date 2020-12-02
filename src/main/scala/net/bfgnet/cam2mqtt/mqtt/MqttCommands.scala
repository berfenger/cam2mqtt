package net.bfgnet.cam2mqtt.mqtt

import akka.stream.alpakka.mqtt.MqttMessage
import net.bfgnet.cam2mqtt.camera.CameraManProtocol.RouteCameraCommand
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraModuleAction
import net.bfgnet.cam2mqtt.camera.modules.{CameraModules, MqttCameraModule}
import net.bfgnet.cam2mqtt.system.O2MActorSystem

object MqttCommands {

    def inputCommand(baseTopic: String, cmd: MqttMessage) = {
        cmd.topic.split("/").toList match {
            case base :: "camera" :: cameraId :: "command" :: moduleId :: cmdByPath :: other if base == baseTopic =>
                CameraModules.MODULES
                        .find(_.moduleId == moduleId)
                        .filter(_.isInstanceOf[MqttCameraModule])
                        .flatMap {
                            _.asInstanceOf[MqttCameraModule].parseMQTTCommand(List(cmdByPath) ++ other, cmd.payload.utf8String)
                        }
                        .map(CameraModuleAction(cameraId, moduleId, _))
                        .foreach { cmd =>
                            println(s"MQTT COMMAND: ${cmd}")
                            O2MActorSystem.sendToCameraMan(RouteCameraCommand(cameraId, cmd))
                        }
            case _ =>
                // discard message
        }
    }

}
