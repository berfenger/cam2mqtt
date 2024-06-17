package net.bfgnet.cam2mqtt
package camera.modules

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import camera.CameraConfig.{CameraInfo, CameraModuleConfig}
import camera.CameraProtocol.CameraCmd

trait CameraModule {

    val moduleId: String

    def createBehavior(camera: ActorRef[CameraCmd], info: CameraInfo, config: CameraModuleConfig): Behavior[CameraCmd]

    def loadConfiguration(from: Map[String, Any]): CameraModuleConfig
}
