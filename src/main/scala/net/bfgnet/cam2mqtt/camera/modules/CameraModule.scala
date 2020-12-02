package net.bfgnet.cam2mqtt.camera.modules

import akka.actor.typed.{ActorRef, Behavior}
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol.CameraActionRequest
import net.bfgnet.cam2mqtt.camera.CameraConfig.{CameraInfo, CameraModuleConfig}
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraCmd

trait CameraModule {

    val moduleId: String

    def createBehavior(camera: ActorRef[CameraCmd], info: CameraInfo, config: CameraModuleConfig): Behavior[CameraCmd]

    def loadConfiguration(from: Map[String, Any]): CameraModuleConfig
}
