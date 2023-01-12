package net.bfgnet.cam2mqtt.camera.modules.reolink

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.alpakka.mqtt.MqttMessage
import akka.util.ByteString
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol._
import net.bfgnet.cam2mqtt.camera.CameraConfig.{CameraInfo, CameraModuleConfig, ReolinkCameraModuleConfig}
import net.bfgnet.cam2mqtt.camera.CameraProtocol._
import net.bfgnet.cam2mqtt.camera.modules.reolink.ReolinkAIDetectionTrackingActor.{AITrackerCmd, ReolinkAIMotionDetectionStateUpdate}
import net.bfgnet.cam2mqtt.camera.modules.{CameraModule, MqttCameraModule}
import net.bfgnet.cam2mqtt.camera.{CameraActionProtocol, CameraProtocol}
import net.bfgnet.cam2mqtt.reolink.{GetAiStateParams, ReolinkCmdResponse, ReolinkHost, ReolinkRequests}
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

sealed trait ReolinkResponse

object AiDetectionMode extends Enumeration {
    type AiDetectionMode = Value
    val UnSupported, Available, Continuous, OnMotion = Value
}

object ReolinkCapabilities {
    def defaultCapabilities: ReolinkCapabilities = ReolinkCapabilities(nightVision = false, irlights = false,
        motionSens = false, ftp = false, ftpV20 = false, record = false, recordV20 = false, ptzZoom = false,
        aiDetection = false, spotlight = false, audio = false, alarm = false)

    def defaultState: ReolinkState = ReolinkState(None, None, None, None, None, None, AiDetectionMode.UnSupported,
        None, None, None, None)
}

case class ReolinkCapabilities(nightVision: Boolean, irlights: Boolean, motionSens: Boolean, ftp: Boolean,
                               ftpV20: Boolean, record: Boolean, recordV20: Boolean, ptzZoom: Boolean,
                               aiDetection: Boolean, spotlight: Boolean, audio: Boolean, alarm: Boolean)

case class ReolinkState(nightVision: Option[NightVisionMode.Value], irlights: Option[Boolean], motionSens: Option[Int],
                        ftp: Option[Boolean], record: Option[Boolean], zoomAbsLevel: Option[Int],
                        aiDetectionMode: AiDetectionMode.Value, aiDetectionState: Option[GetAiStateParams],
                        spotlightState: Option[Boolean], spotlightBrightness: Option[Int], audioVolume: Option[Int])

case class ReolinkInitialState(caps: ReolinkCapabilities, state: ReolinkState)

case class GenericReolinkCmdResponse(req: CameraActionRequest, result: CameraActionResponse) extends ReolinkResponse

object ReolinkModule extends CameraModule with MqttCameraModule with ActorContextImplicits {
    override val moduleId: String = "reolink"

    private val CAM_PARAM_NIGHTVISION = "nightvision"
    private val CAM_PARAM_IRLIGHTS = "irlights"
    private val CAM_PARAM_MOTION_SENS = "motion/sensitivity"
    private val CAM_PARAM_FTP = "ftp"
    private val CAM_PARAM_RECORD = "record"
    private val CAM_PARAM_AI_DETECTION_MODE = "ai_detection_mode"
    private val CAM_PARAM_ZOOM_ABS = "ptz/zoom/absolute"
    private val CAM_PARAM_AIDETECTION_STATE = "aidetection"
    private val CAM_PARAM_SPOTLIGHT_STATE = "spotlight/state"
    private val CAM_PARAM_SPOTLIGHT_BRIGHTNESS = "spotlight/brightness"
    private val CAM_PARAM_AUDIO_VOLUME = "audio/volume"

    private val CAM_PARAMS = List(CAM_PARAM_NIGHTVISION, CAM_PARAM_IRLIGHTS, CAM_PARAM_MOTION_SENS, CAM_PARAM_FTP,
        CAM_PARAM_RECORD, CAM_PARAM_ZOOM_ABS, CAM_PARAM_AI_DETECTION_MODE, CAM_PARAM_SPOTLIGHT_STATE,
        CAM_PARAM_SPOTLIGHT_BRIGHTNESS, CAM_PARAM_AUDIO_VOLUME)

    private val NIGHVISION_VALUES = List("on", "off", "auto")

    private val ONOFF_VALUES = List("on", "off")

    case class Setup(parent: ActorRef[CameraCmd], camera: CameraInfo, host: ReolinkHost, config: ReolinkCameraModuleConfig)

    override def createBehavior(camera: ActorRef[CameraCmd], info: CameraInfo, config: CameraModuleConfig): Behavior[CameraCmd] = {

        val modCfg = config match {
            case c: ReolinkCameraModuleConfig => c
            case _ => ReolinkCameraModuleConfig(None, None, None, None, syncDateTime = false, None,
                enableSpotlight = false, enableAudio = false, enableAlarm = false)
        }
        val reoHost = ReolinkHost(info.host,
            modCfg.port.getOrElse(443),
            modCfg.altUsername.getOrElse(info.username),
            modCfg.altPassword.getOrElse(info.password),
            modCfg.useSSL.getOrElse(modCfg.port.forall(_ == 443))
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
                case Success(value) =>
                    WrappedModuleCmd(value)
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
                    if (cmd.caps.ftp || cmd.caps.ftpV20) {
                        cmd.state.ftp.foreach {
                            updateFTPState(setup)
                        }
                    }
                    if (cmd.caps.record || cmd.caps.recordV20) {
                        cmd.state.record.foreach {
                            updateRecordState(setup)
                        }
                    }
                    if (cmd.caps.spotlight && setup.config.enableSpotlight) {
                        cmd.state.spotlightState.foreach {
                            updateSpotlightState(setup)
                        }
                        cmd.state.spotlightBrightness.foreach {
                            updateSpotlightBrightness(setup)
                        }
                    }
                    if (cmd.caps.audio && setup.config.enableAudio) {
                        cmd.state.audioVolume.foreach {
                            updateAudioVolume(setup)
                        }
                    }
                    // Combine config.aiDetectionMode with detected capabilities
                    val aiDetectionModeMod = if (cmd.state.aiDetectionMode != AiDetectionMode.UnSupported) {
                        val ais = setup.config.aiDetectionMode.getOrElse(cmd.state.aiDetectionMode)
                        updateAiDetectionMode(setup)(ais)
                        ais
                    } else AiDetectionMode.UnSupported
                    // init ReolinkAIDetectionTrackingActor if mode is continuous
                    val aiTrackerActor = if (aiDetectionModeMod == AiDetectionMode.Continuous) {
                        Some(context.spawn(ReolinkAIDetectionTrackingActor(context.self, setup.host), "reolinkAITracker"))
                    } else None
                    // combine capabilities with module config
                    val capsMod = cmd.caps.copy(spotlight = cmd.caps.spotlight && setup.config.enableSpotlight,
                        audio = cmd.caps.audio && setup.config.enableAudio,
                        alarm = setup.config.enableAlarm)
                    // log final capabilities and move to awaitingCommand
                    logCapabilities(setup.camera.cameraId, capsMod)
                    awaitingCommand(setup.copy(config = setup.config.copy(aiDetectionMode = Some(aiDetectionModeMod))), capsMod, aiTrackerActor)
                case TerminateCam =>
                    Behaviors.stopped
            }
        }
    }

    private def awaitingCommand(setup: Setup, caps: ReolinkCapabilities, aiDetectionTrackingActor: Option[ActorRef[AITrackerCmd]]): Behavior[CameraCmd] = {
        Behaviors.setup { implicit context =>
            Behaviors.receiveMessagePartial[CameraCmd] {
                case CameraModuleAction(_, _, req@SetNightVisionActionRequest(mode, replyTo)) if caps.nightVision =>
                    val a = ReolinkRequests.setNightVision(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@PTZMoveActionRequest(pt, z, abs, replyTo)) if caps.ptzZoom =>
                    if (pt.isDefined || !abs || z.isEmpty) {
                        replyTo.foreach(_ ! CameraActionResponse(Left(new IllegalArgumentException("only absolute zoom is currently supported"))))
                        Behaviors.same
                    } else {
                        val a = ReolinkRequests.setZoom(setup.host, z.get.z)
                        context.pipeToSelf(a)(wrapGenericResponse(req))
                        awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                    }
                case CameraModuleAction(_, _, req@PTZMoveActionRequest(_, _, _, replyTo)) if !caps.ptzZoom =>
                    replyTo.foreach(_ ! CameraActionResponse(Left(new IllegalArgumentException("camera does not support PTZ control"))))
                    Behaviors.same
                case CameraModuleAction(_, _, req@SetIrLightsActionRequest(mode, replyTo)) if caps.irlights =>
                    val a = ReolinkRequests.setIrLights(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@SetMotionSensActionRequest(mode, replyTo)) if caps.motionSens =>
                    val a = ReolinkRequests.setAlarmSens(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@SetFTPEnabledActionRequest(mode, replyTo)) if caps.ftp || caps.ftpV20 =>
                    val a = if (caps.ftp) ReolinkRequests.setFTPEnabled(setup.host, mode)
                    else ReolinkRequests.setFTPV20Enabled(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@SetRecordEnabledActionRequest(mode, replyTo)) if caps.record || caps.recordV20 =>
                    val a = if (caps.record) ReolinkRequests.setRecordEnabled(setup.host, mode)
                    else ReolinkRequests.setRecordV20Enabled(setup.host, mode)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@SetSpotlightActionRequest(enabled, brightness, replyTo)) if caps.spotlight =>
                    val a = ReolinkRequests.setWhiteLed(setup.host, enabled = enabled, brightness = brightness)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@SetAudioVolumeActionRequest(volume, replyTo)) if caps.spotlight =>
                    val a = ReolinkRequests.setAudioCfg(setup.host, volume)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req@PlayAlarmActionRequest(play, times, replyTo)) if caps.spotlight =>
                    val a = ReolinkRequests.setAudioAlarmPlay(setup.host, play, times)
                    context.pipeToSelf(a)(wrapGenericResponse(req))
                    awaitingCommandResult(setup, caps, aiDetectionTrackingActor, replyTo)
                case CameraModuleAction(_, _, req) =>
                    req.replyTo.foreach(_ ! CameraActionResponse(Left(new IllegalArgumentException("operation not supported by camera"))))
                    Behaviors.same
                case CameraModuleEvent(_, _, CameraMotionEvent(_, _, motion)) =>
                    if (caps.aiDetection && motion && aiDetectionTrackingActor.isEmpty &&
                            setup.config.aiDetectionMode.contains(AiDetectionMode.OnMotion)) {
                        // start AITracker if device has capability
                        val ai = context.spawn(ReolinkAIDetectionTrackingActor(context.self, setup.host), "reolinkAITracker")
                        awaitingCommand(setup, caps, Some(ai))
                    } else if (caps.aiDetection && !motion && aiDetectionTrackingActor.isDefined &&
                            setup.config.aiDetectionMode.contains(AiDetectionMode.OnMotion)) {
                        // stop AITracker if spawned
                        aiDetectionTrackingActor.foreach(_ ! ReolinkAIDetectionTrackingActor.Terminate)
                        awaitingCommand(setup, caps, None)
                    } else {
                        Behaviors.same
                    }
                case WrappedModuleCmd(ReolinkAIMotionDetectionStateUpdate(k, motion)) =>
                    // publish AI detection state to MQTT
                    updateAIDetectionState(setup)(k, motion)
                    Behaviors.same
                case TerminateCam =>
                    Behaviors.stopped
            }
        }
    }

    private def awaitingCommandResult(setup: Setup, caps: ReolinkCapabilities, aiDetectionTrackingActor: Option[ActorRef[AITrackerCmd]],
                                      replyTo: Option[ActorRef[CameraActionResponse]]): Behavior[CameraCmd] = {
        Behaviors.withStash(100) { buffer =>
            Behaviors.receiveMessagePartial {
                case WrappedModuleCmd(resp: CameraActionResponse) =>
                    replyTo.foreach(_ ! resp)
                    buffer.unstashAll(awaitingCommand(setup, caps, aiDetectionTrackingActor))
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
                            case SetSpotlightActionRequest(enabled, brightness, _) =>
                                enabled.foreach(updateSpotlightState(setup))
                                brightness.foreach(updateSpotlightBrightness(setup))
                            case SetAudioVolumeActionRequest(volume, _) =>
                                updateAudioVolume(setup)(volume)
                            case _ =>
                        }
                    }
                    replyTo.foreach(_ ! result)
                    buffer.unstashAll(awaitingCommand(setup, caps, aiDetectionTrackingActor))
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
        val ssl = from.get("ssl").filter(_ != null).map(_.toString).filter(v => v == "true" || v == "false").map(v => if (v == "true") true else false)
        val username = from.get("username").filter(_ != null).map(_.toString).filter(_.nonEmpty)
        val password = from.get("password").filter(_ != null).map(_.toString).filter(_.nonEmpty)
        val aiDetectionMode = from.get("ai_detection_mode").filter(_ != null).map(_.toString).filter(_.nonEmpty).map(_.toLowerCase()) match {
            case Some("off") | Some("onvif") | Some("available") => Some(AiDetectionMode.Available)
            case Some("on_motion") => Some(AiDetectionMode.OnMotion)
            case Some("continuous") => Some(AiDetectionMode.Continuous)
            case _ => None
        }
        val audio = from.get("audio").filter(_ != null).map(_.toString).filter(v => v == "true" || v == "false").map(v => if (v == "true") true else false)
        val alarm = from.get("alarm").filter(_ != null).map(_.toString).filter(v => v == "true" || v == "false").map(v => if (v == "true") true else false)
        val spotlight = from.get("spotlight").filter(_ != null).map(_.toString).filter(v => v == "true" || v == "false").map(v => if (v == "true") true else false)
        ReolinkCameraModuleConfig(port, ssl, username, password, syncDateTime, aiDetectionMode,
            spotlight.getOrElse(false), audio.getOrElse(false), alarm.getOrElse(false))
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
            case "spotlight" :: "state" :: Nil if ONOFF_VALUES.contains(stringData) =>
                val nv = stringData match {
                    case "on" => true
                    case "off" => false
                }
                Some(SetSpotlightActionRequest(Option(nv), None, None))
            case "spotlight" :: "brightness" :: Nil =>
                Try(stringData.toInt).toOption.filter(v => v >= 0 && v <= 100).map { v =>
                    SetSpotlightActionRequest(None, Some(v), None)
                }
            case "audio" :: "volume" :: Nil =>
                Try(stringData.toInt).toOption.filter(v => v >= 0 && v <= 100).map { v =>
                    SetAudioVolumeActionRequest(v, None)
                }
            case "alarm" :: "play" :: Nil if ONOFF_VALUES.contains(stringData) =>
                val nv = stringData match {
                    case "on" => true
                    case "off" => false
                }
                Some(PlayAlarmActionRequest(nv, None, None))
            case "alarm" :: "play" :: Nil =>
                Try(stringData.toInt).toOption.filter(v => v >= 0 && v <= 100).map { v =>
                    PlayAlarmActionRequest(play = true, Some(v), None)
                }
            case _ => None
        }
    }

    override def eventToMqttMessage(ev: CameraProtocol.CameraEvent): Option[MqttMessage] = ev match {
        case CameraStateBoolEvent(cameraId, moduleId, param, state) if param.startsWith(CAM_PARAM_AIDETECTION_STATE) =>
            val str = if (state) "on" else "off"
            Some(MqttMessage(s"${cameraEventModulePath(cameraId, moduleId)}/$param/detected", ByteString(str)))
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

    private def updateIRLightsState(setup: Setup)(enabled: Boolean): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_IRLIGHTS, enabled))
    }

    private def updateZoomState(setup: Setup)(zoomLevel: Int): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateIntEvent(setup.camera.cameraId, moduleId, CAM_PARAM_ZOOM_ABS, zoomLevel))
    }

    private def updateMotionSensState(setup: Setup)(motionSens: Int): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateIntEvent(setup.camera.cameraId, moduleId, CAM_PARAM_MOTION_SENS, motionSens))
    }

    private def updateFTPState(setup: Setup)(enabled: Boolean): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_FTP, enabled))
    }

    private def updateRecordState(setup: Setup)(enabled: Boolean): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_RECORD, enabled))
    }

    private def updateSpotlightState(setup: Setup)(enabled: Boolean): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, CAM_PARAM_SPOTLIGHT_STATE, enabled))
    }

    private def updateSpotlightBrightness(setup: Setup)(brightness: Int): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateIntEvent(setup.camera.cameraId, moduleId, CAM_PARAM_SPOTLIGHT_BRIGHTNESS, brightness))
    }

    private def updateAudioVolume(setup: Setup)(volume: Int): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateIntEvent(setup.camera.cameraId, moduleId, CAM_PARAM_AUDIO_VOLUME, volume))
    }

    private def updateAiDetectionMode(setup: Setup)(aiDetectionMode: AiDetectionMode.Value): Unit = {
        val v = aiDetectionMode match {
            case AiDetectionMode.UnSupported => "unsupported"
            case AiDetectionMode.OnMotion => "on_motion"
            case AiDetectionMode.Continuous => "continuous"
            case AiDetectionMode.Available => "off"
        }
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateStringEvent(setup.camera.cameraId, moduleId, CAM_PARAM_AI_DETECTION_MODE, v))
    }

    private def updateAIDetectionState(setup: Setup)(aiKey: String, motion: Boolean): Unit = {
        setup.parent ! CameraModuleEvent(setup.camera.cameraId, moduleId, CameraStateBoolEvent(setup.camera.cameraId, moduleId, s"$CAM_PARAM_AIDETECTION_STATE/$aiKey", motion))
    }

    private def logCapabilities(cameraId: String, caps: ReolinkCapabilities)(implicit _ac: ActorContext[_]) = {
        val c = new mutable.StringBuilder()
        c ++= s"Reolink Capabilities for camera $cameraId:\n"
        if (caps.irlights) {
            c ++= s"IR lights: ${caps.irlights}\n"
        }
        if (caps.nightVision) {
            c ++= s"Night vision: ${caps.nightVision}\n"
        }
        if (caps.motionSens) {
            c ++= s"Motion Sensitivity: ${caps.motionSens}\n"
        }
        if (caps.record) {
            c ++= s"Record: ${caps.record}\n"
        } else if (caps.recordV20) {
            c ++= s"Record (V2): ${caps.recordV20}\n"
        }
        if (caps.ftp) {
            c ++= s"FTP: ${caps.ftp}\n"
        } else if (caps.ftpV20) {
            c ++= s"FTP (V2): ${caps.ftpV20}\n"
        }
        if (caps.ptzZoom) {
            c ++= s"PTZ Zoom: ${caps.ptzZoom}\n"
        }
        if (caps.aiDetection) {
            c ++= s"AI Detection: ${caps.aiDetection}\n"
        }
        if (caps.spotlight) {
            c ++= s"Spotlight: ${caps.spotlight}\n"
        }
        if (caps.audio) {
            c ++= s"Audio: ${caps.audio}\n"
        }
        if (caps.alarm) {
            c ++= s"Alarm: ${caps.alarm}\n"
        }
        _ac.log.info(c.toString().trim)
    }
}
