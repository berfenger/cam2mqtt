package net.bfgnet.cam2mqtt
package mqtt

import org.apache.pekko.stream.connectors.mqtt.MqttMessage
import camera.CameraManProtocol.RouteCameraCommand
import camera.CameraProtocol.CameraModuleAction
import camera.modules.{CameraModules, MqttCameraModule}
import system.O2MActorSystem

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
                            O2MActorSystem.sendToCameraMan(RouteCameraCommand(cameraId, cmd))
                        }
            case _ =>
                // discard message
        }
    }

}
