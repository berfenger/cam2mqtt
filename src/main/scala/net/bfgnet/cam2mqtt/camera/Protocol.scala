package net.bfgnet.cam2mqtt.camera

import akka.actor.typed.ActorRef
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol.CameraActionRequest
import net.bfgnet.cam2mqtt.camera.CameraConfig.CameraInfo
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraCmd
import net.bfgnet.cam2mqtt.camera.modules.generic.GenericMqttCamModule
import net.bfgnet.cam2mqtt.camera.modules.onvif.OnvifModule
import net.bfgnet.cam2mqtt.camera.modules.reolink.{AiDetectionMode, ReolinkModule}

object CameraManProtocol {

    sealed trait CameraManCmd

    case class InitCam(camera: CameraInfo) extends CameraManCmd

    case class StopCam(cameraId: String) extends CameraManCmd

    case class RouteCameraCommand(cameraId: String, message: CameraCmd) extends CameraManCmd

    case object Terminate extends CameraManCmd

}

object CameraProtocol {

    sealed trait CameraCmd

    case class CameraModuleAction(cameraId: String, moduleId: String, command: CameraActionRequest) extends CameraCmd

    case class CameraModuleMessage(cameraId: String, moduleId: String, message: String) extends CameraCmd

    case class CameraModuleEvent(cameraId: String, moduleId: String, event: CameraEvent) extends CameraCmd

    case class WrappedModuleCmd(cmd: Any) extends CameraCmd

    case object TerminateCam extends CameraCmd

    sealed trait CameraEvent {
        val cameraId: String
        val moduleId: String
    }

    case class CameraMotionEvent(override val cameraId: String, override val moduleId: String, motion: Boolean) extends CameraEvent

    case class CameraObjectDetectionEvent(override val cameraId: String, override val moduleId: String, objectClass: String, detection: Boolean) extends CameraEvent

    case class CameraAvailableEvent(override val cameraId: String, available: Boolean) extends CameraEvent {
        override val moduleId: String = GenericMqttCamModule.moduleId
    }

    case class CameraStateBoolEvent(override val cameraId: String, override val moduleId: String, param: String, state: Boolean) extends CameraEvent

    case class CameraStateIntEvent(override val cameraId: String, override val moduleId: String, param: String, state: Int) extends CameraEvent

    case class CameraStateStringEvent(override val cameraId: String, override val moduleId: String, param: String, state: String) extends CameraEvent

}

object CameraActionProtocol {

    sealed trait CameraActionRequest {
        val replyTo: Option[ActorRef[CameraActionResponse]]
    }

    // unique response to simplify actor message management
    case class CameraActionResponse(result: Either[Throwable, String])

    // PTZ Model
    case class PTVector(x: Int, y: Int)

    case class ZVector(z: Int)

    // PTZ Move
    case class PTZMoveActionRequest(pt: Option[PTVector], z: Option[ZVector], isAbsolute: Boolean, override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

    // Night vision
    object NightVisionMode extends Enumeration {
        val ForceOn, ForceOff, Auto = Value
    }

    case class SetNightVisionActionRequest(mode: NightVisionMode.Value, override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

    case class SetIrLightsActionRequest(enabled: Boolean, override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

    case class SetSpotlightActionRequest(enabled: Option[Boolean], brightness: Option[Int], override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

    // Motion record
    case class SetMotionSensActionRequest(sens: Int, override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

    case class SetFTPEnabledActionRequest(enabled: Boolean, override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

    case class SetRecordEnabledActionRequest(enabled: Boolean, override val replyTo: Option[ActorRef[CameraActionResponse]]) extends CameraActionRequest

}

object CameraConfig {

    trait CameraModuleConfig {
        val moduleId: String
        type ModConf <: CameraModuleConfig
        def copyWithPrivacy(): ModConf
    }

    case class CameraInfo(cameraId: String, host: String, username: String, password: String, modules: List[CameraModuleConfig]) {
        def copyWithPrivacy(): CameraInfo = this.copy(password = "redacted", modules = modules.map(_.copyWithPrivacy()))
    }

    case class OnvifCameraModuleConfig(port: Int, monitorEvents: Boolean, preferWebhookSub: Boolean) extends CameraModuleConfig {
        override val moduleId: String = OnvifModule.moduleId

        override type ModConf = OnvifCameraModuleConfig

        override def copyWithPrivacy(): OnvifCameraModuleConfig = this
    }

    case class ReolinkCameraModuleConfig(port: Option[Int], useSSL: Option[Boolean],
                                         altUsername: Option[String], altPassword: Option[String],
                                         syncDateTime: Boolean, aiDetectionMode: Option[AiDetectionMode.Value]) extends CameraModuleConfig {
        override val moduleId: String = ReolinkModule.moduleId

        override type ModConf = ReolinkCameraModuleConfig

        override def copyWithPrivacy(): ReolinkCameraModuleConfig = this.copy(altPassword = this.altPassword.map(_ => "redacted"))
    }

}
