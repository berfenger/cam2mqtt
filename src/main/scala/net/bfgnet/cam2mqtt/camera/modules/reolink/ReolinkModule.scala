package net.bfgnet.cam2mqtt.camera.modules.reolink

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol._
import net.bfgnet.cam2mqtt.camera.CameraConfig.{CameraInfo, CameraModuleConfig, ReolinkCameraModuleConfig}
import net.bfgnet.cam2mqtt.camera.CameraProtocol._
import net.bfgnet.cam2mqtt.camera.modules.{CameraModule, MqttCameraModule}
import net.bfgnet.cam2mqtt.camera.{CameraActionProtocol, CameraProtocol}
import net.bfgnet.cam2mqtt.reolink.{ReolinkCmdResponse, ReolinkHost, ReolinkRequests}
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits

import scala.util.{Failure, Success, Try}

sealed trait ReolinkResponse

object ReolinkCapabilities {
    def defaultCapabilities: ReolinkCapabilities = ReolinkCapabilities(nightVision = false, irlights = false,
        motionSens = false, ftp = false, record = false, ptzZoom = false)

    def defaultState: ReolinkState = ReolinkState(None, None, None, None, None, None)
}

case class ReolinkCapabilities(nightVision: Boolean, irlights: Boolean, motionSens: Boolean, ftp: Boolean, record: Boolean, ptzZoom: Boolean)

case class ReolinkState(nightVision: Option[NightVisionMode.Value], irlights: Option[Boolean], motionSens: Option[Int],
                        ftp: Option[Boolean], record: Option[Boolean], zoomAbsLevel: Option[Int])

case class ReolinkInitialState(caps: ReolinkCapabilities, state: ReolinkState)

case class GenericReolinkCmdResponse(req: CameraActionRequest, result: CameraActionResponse) extends ReolinkResponse

object ReolinkModule extends CameraModule with MqttCameraModule with ActorContextImplicits {
    override val moduleId: String = "reolink"

    private val CAM_PARAM_NIGHTVISION = "nightvision"
    private val CAM_PARAM_IRLIGHTS = "irlights"
    private val CAM_PARAM_MOTION_SENS = "motion/sensitivity"
    private val CAM_PARAM_FTP = "ftp"
    private val CAM_PARAM_RECORD = "record"
    private val CAM_PARAM_ZOOM_ABS = "ptz/zoom/absolute"

    private val CAM_PARAMS = List(CAM_PARAM_NIGHTVISION, CAM_PARAM_IRLIGHTS, CAM_PARAM_MOTION_SENS, CAM_PARAM_FTP, CAM_PARAM_RECORD, CAM_PARAM_ZOOM_ABS)

    private val NIGHVISION_VALUES = List("on", "off", "auto")

    private val ONOFF_VALUES = List("on", "off")

    case class Setup(parent: ActorRef[CameraCmd], camera: CameraInfo, host: ReolinkHost, config: ReolinkCameraModuleConfig)

    override def createBehavior(camera: ActorRef[CameraCmd], info: CameraInfo, config: CameraModuleConfig): Behavior[CameraCmd] = {

        val modCfg = config match {
            case c: ReolinkCameraModuleConfig => c
            case _ => ReolinkCameraModuleConfig(None, None, None, None, syncDateTime = false)
        }
        val reoHost = ReolinkHost(info.host,
            modCfg.port.getOrElse(80),
            modCfg.altUsername.getOrElse(info.username),
            modCfg.altPassword.getOrElse(info.password),
            modCfg.useSSL.getOrElse(false)
        )
        val setup = Setup(camera, info, reoHost, modCfg)
        if (modCfg.syncDateTime) {
            initWithSyncTime(setup)
        } else {
            gettingState(Setup(camera, info, reoHost, modCfg))
        }
    }

    private def initWithSyncTime(setup: Setup): Behavior[CameraCmd] = {
        Behaviors.setup { implicit context =>
            Behaviors.withStash(100) { buffer =>
                val f = ReolinkRequests.updateTimeIfNeeded(setup.host, maxSkewMillis = 5000)
                context.pipeToSelf(f) {
                    case Success(None) => WrappedModuleCmd("nu")
                    case Success(Some(r)) if r.isOk => WrappedModuleCmd(if (r.isOk) "ok" else r.errorException)
                    case Success(Some(r)) => WrappedModuleCmd(r.errorException)
                    case Failure(exception) => WrappedModuleCmd(Option(exception))
                }
                Behaviors.receiveMessagePartial[CameraCmd] {
                    case WrappedModuleCmd(Some(err: Throwable)) =>
                        // on error, just print it. restarting won't get any better
                        context.log.error("error updating time", err)
                        gettingState(setup)
                    case WrappedModuleCmd(None) =>
                        gettingState(setup)
                    case WrappedModuleCmd("nu") =>
                        context.log.info(s"no need to update camera time on ${setup.camera.cameraId}")
                        gettingState(setup)
                    case WrappedModuleCmd("ok") =>
                        context.log.info(s"time updated on camera ${setup.camera.cameraId}")
                        throw new Exception("force camera restart as this can cause problems with existintg subscription")
                    case TerminateCam =>
                        Behaviors.stopped
                    case other =>
                        buffer.stash(other)
                        Behaviors.same
                }
            }
        }
    }

    private def gettingState(setup: Setup): Behavior[CameraCmd] = {
        Behaviors.setup { implicit context =>
            // launch get capabilities and state
            context.pipeToSelf(ReolinkRequests.getCapabilities(setup.host).map(r => ReolinkInitialState(r._1, r._2))) {
                case Success(value) => WrappedModuleCmd(value)
                case Failure(exception) =>
                    context.log.error(s"could not get reolink capabilities from device ${setup.camera.cameraId}", exception)
                    WrappedModuleCmd(ReolinkInitialState(ReolinkCapabilities.defaultCapabilities, ReolinkCapabilities.defaultState))
            }
            Behaviors.receiveMessagePartial[CameraCmd] {
                case WrappedModuleCmd(cmd: ReolinkInitialState) =>
                    // send all state events to camera
                    if (cmd.caps.irlights) {
                        cmd.state.irlights.foreach {
                            updateIRLightsState(setup)
                        }
                    }
                    if (cmd.caps.nightVision) {
                        cmd.state.nightVision.foreach {
                            updateNightVisionState(setup)
                        }
                    }
                    if (cmd.caps.ptzZoom) {
                        cmd.state.zoomAbsLevel.foreach {
                            updateZoomState(setup)
                        }
                    }
                    if (cmd.caps.motionSens) {
                        cmd.state.motionSens.foreach {
                            updateMotionSensState(setup)
                        }
                    }
                    if (cmd.caps.ftp) {
                        cmd.state.ftp.foreach {
                            updateFTPState(setup)
                        }
                    }
                    if (cmd.caps.record) {
                        cmd.state.record.foreach {
                            updateRecordState(setup)
                        }
                    }
                    awaitingCommand(setup, cmd.caps)
                case TerminateCam =>
                    Behaviors.stopped
            }
        }
    }

    private def awaitingCommand(setup: Setup, caps: ReolinkCapabilities): Behavior[CameraCmd] = {
        Behaviors.setup { implicit context =>
            Behaviors.receiveMessagePartial[CameraCmd] {
                case CameraModuleAction(_, _, req@SetNightVisionActionRequest(mode, replyTo)) if caps.nightVision =>
                    val a = ReolinkRequests.setNightVision(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, replyTo)
                case CameraModuleAction(_, _, req@PTZMoveActionRequest(pt, z, abs, replyTo)) if caps.ptzZoom =>
                    if (pt.isDefined || !abs || z.isEmpty) {
                        replyTo.foreach(_ ! CameraActionResponse(Left(new IllegalArgumentException("only absolute zoom is currently supported"))))
                        Behaviors.same
                    } else {
                        val a = ReolinkRequests.setZoom(setup.host, z.get.z)
                        context.pipeToSelf(a)(wrapGenericResponse(req))
                        awaitingCommandResult(setup, caps, replyTo)
                    }
                case CameraModuleAction(_, _, req@PTZMoveActionRequest(_, _, _, replyTo)) if !caps.ptzZoom =>
                    replyTo.foreach(_ ! CameraActionResponse(Left(new IllegalArgumentException("camera does not support PTZ control"))))
                    Behaviors.same
                case CameraModuleAction(_, _, req@SetIrLightsActionRequest(mode, replyTo)) if caps.irlights =>
                    val a = ReolinkRequests.setIrLights(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, replyTo)
                case CameraModuleAction(_, _, req@SetMotionSensActionRequest(mode, replyTo)) if caps.motionSens =>
                    val a = ReolinkRequests.setAlarmSens(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, replyTo)
                case CameraModuleAction(_, _, req@SetFTPEnabledActionRequest(mode, replyTo)) if caps.ftp =>
                    val a = ReolinkRequests.setFTPEnabled(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, replyTo)
                case CameraModuleAction(_, _, req@SetRecordEnabledActionRequest(mode, replyTo)) if caps.record =>
                    val a = ReolinkRequests.setRecordEnabled(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, replyTo)
                case CameraModuleAction(_, _, req) =>
                    req.replyTo.foreach(_ ! CameraActionResponse(Left(new IllegalArgumentException("operation not supported by camera"))))
                    Behaviors.same
                case TerminateCam =>
                    Behaviors.stopped
            }
        }
    }

    private def awaitingCommandResult(setup: Setup, caps: ReolinkCapabilities, replyTo: Option[ActorRef[CameraActionResponse]]): Behavior[CameraCmd] = {
        Behaviors.withStash(100) { buffer =>
            Behaviors.receiveMessagePartial {
                case WrappedModuleCmd(resp: CameraActionResponse) =>
                    replyTo.foreach(_ ! resp)
                    buffer.unstashAll(awaitingCommand(setup, caps))
                case WrappedModuleCmd(GenericReolinkCmdResponse(req, result)) =>
                    // if result is OK, update state
                    if (result.result.isRight) {
                        req match {
                            case SetNightVisionActionRequest(mode, _) =>
                                updateNightVisionState(setup)(mode)
                            case SetIrLightsActionRequest(enabled, _) =>
                                updateIRLightsState(setup)(enabled)
                            case SetMotionSensActionRequest(sens, _) =>
                                updateMotionSensState(setup)(sens)
                            case PTZMoveActionRequest(pt, Some(zoomLevel), true, _) =>
                                updateZoomState(setup)(zoomLevel.z)
                            case SetFTPEnabledActionRequest(enabled, _) =>
                                updateFTPState(setup)(enabled)
                            case SetRecordEnabledActionRequest(enabled, _) =>
                                updateRecordState(setup)(enabled)
                            case _ =>
                        }
                    }
                    replyTo.foreach(_ ! result)
                    buffer.unstashAll(awaitingCommand(setup, caps))
                case TerminateCam =>
                    Behaviors.stopped
                case other =>
                    buffer.stash(other)
                    Behaviors.same
            }
        }
    }

    private def wrapGenericResponse(req: CameraActionRequest)(resp: Try[ReolinkCmdResponse]): CameraCmd = resp match {
        case Success(value) if value.isOk =>
            WrappedModuleCmd(GenericReolinkCmdResponse(req, CameraActionResponse(Right(value.value.rspCode.toString))))
        case Success(value) =>
            WrappedModuleCmd(GenericReolinkCmdResponse(req, CameraActionResponse(Left(value.errorException.getOrElse(new Exception("unknown error"))))))
        case Failure(err) => WrappedModuleCmd(GenericReolinkCmdResponse(req, CameraActionResponse(Left(err))))
    }

    override def loadConfiguration(from: Map[String, Any]): CameraModuleConfig = {
        val syncDateTime = from.get("sync_datetime").filter(_ != null).map(_.toString).contains("true")
        val port = from.get("port").filter(_ != null).flatMap(v => Try(v.toString.toInt).toOption)
        val ssl = from.get("use_ssl").filter(_ != null).map(_.toString).filter(v => v == "true" || v == "false").map(v => if (v == "true") true else false)
        val username = from.get("username").filter(_ != null).map(_.toString).filter(_.length > 0)
        val password = from.get("password").filter(_ != null).map(_.toString).filter(_.length > 0)
        ReolinkCameraModuleConfig(port, ssl, username, password, syncDateTime)
    }

    override def parseMQTTCommand(path: List[String], stringData: String): Option[CameraActionProtocol.CameraActionRequest] = {
        path match {
            case "nightvision" :: Nil if NIGHVISION_VALUES.contains(stringData) =>
                val nv = stringData match {
                    case "on" => NightVisionMode.ForceOn
                    case "off" => NightVisionMode.ForceOff
                    case "auto" => NightVisionMode.Auto
                }
                Some(SetNightVisionActionRequest(nv, None))
            case "irlights" :: Nil if ONOFF_VALUES.contains(stringData) =>
                val nv = stringData match {
                    case "on" => true
                    case "off" => false
                }
                Some(SetIrLightsActionRequest(nv, None))
            case "motion" :: "sensitivity" :: Nil =>
                val sensLevel = Try(stringData.toInt).toOption.filter(v => v >= 0 && v <= 100)
                sensLevel.map { sl =>
                    SetMotionSensActionRequest(sl, None)
                }
            case "ftp" :: Nil if ONOFF_VALUES.contains(stringData) =>
                val nv = stringData match {
                    case "on" => true
                    case "off" => false
                }
                Some(SetFTPEnabledActionRequest(nv, None))
            case "record" :: Nil if ONOFF_VALUES.contains(stringData) =>
                val nv = stringData match {
                    case "on" => true
                    case "off" => false
                }
                Some(SetRecordEnabledActionRequest(nv, None))
            case "ptz" :: "zoom" :: "absolute" :: Nil =>
                val zoomLevel = Try(stringData.toInt).toOption
                zoomLevel.map { zl =>
                    PTZMoveActionRequest(None, Some(ZVector(zl)), isAbsolute = true, None)
                }
            case _ => None
        }
    }

    override def eventToMqttMessage(ev: CameraProtocol.CameraEvent): Option[MqttMessage] = ev match {
        case CameraStateBoolEvent(cameraId, moduleId, param, state) if CAM_PARAMS.contains(param) =>
            val str = if (state) "on" else "off"
            Some(MqttMessage(s"${cameraStateModulePath(cameraId, moduleId)}/$param", ByteString(str)))
        case CameraStateIntEvent(cameraId, moduleId, param, state) if CAM_PARAMS.contains(param) =>
            Some(MqttMessage(s"${cameraStateModulePath(cameraId, moduleId)}/$param", ByteString(state.toString)))
        case CameraStateStringEvent(cameraId, moduleId, param, state) if CAM_PARAMS.contains(param) =>
            Some(MqttMessage(s"${cameraStateModulePath(cameraId, moduleId)}/$param", ByteString(state)))
        case _ => None
    }

    private def updateNightVisionState(setup: Setup)(mode: NightVisionMode.Value): Unit = {
        val v = mode match {
            case NightVisionMode.ForceOn => "on"
            case NightVisionMode.ForceOff => "off"
            case NightVisionMode.Auto => "auto"
        }
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateStringEvent(setup.camera.cameraId, moduleId, CAM_PARAM_NIGHTVISION, v))
    }

    private def updateIRLightsState(setup: Setup)(enabled: Boolean): Unit  = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_IRLIGHTS, enabled))
    }

    private def updateZoomState(setup: Setup)(zoomLevel: Int): Unit  = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateIntEvent(setup.camera.cameraId, moduleId, CAM_PARAM_ZOOM_ABS, zoomLevel))
    }

    private def updateMotionSensState(setup: Setup)(motionSens: Int): Unit  = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateIntEvent(setup.camera.cameraId, moduleId, CAM_PARAM_MOTION_SENS, motionSens))
    }

    private def updateFTPState(setup: Setup)(enabled: Boolean): Unit  = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_FTP, enabled))
    }

    private def updateRecordState(setup: Setup)(enabled: Boolean): Unit  = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_RECORD, enabled))
    }
}
