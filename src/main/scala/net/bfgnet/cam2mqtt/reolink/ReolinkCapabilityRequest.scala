package net.bfgnet.cam2mqtt.reolink

import akka.actor.ClassicActorSystemProvider
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol.NightVisionMode
import net.bfgnet.cam2mqtt.camera.modules.reolink.{ReolinkCapabilities, ReolinkState}
import org.codehaus.jettison.json.{JSONArray, JSONObject}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait ReolinkCapabilityRequest extends ReolinkRequest {

    def getCapabilities(host: ReolinkHost)
                       (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        val cmds = List(
            ReolinkCmd("GetIrLights", 1, null),
            ReolinkCmd("GetIsp", 1, Channel(0)),
            ReolinkCmd("GetZoomFocus", 1, Channel(0)),
            ReolinkCmd("GetAlarm", 1, GetAlarmCommand(GetAlarmCommandParams(0, "md")))
        )
        for {
            cmdRes <- runGetCommands(host, cmds)
            arr = new JSONArray(cmdRes)
            resultList = (0 until arr.length()).map(arr.getJSONObject).toList.map(parseCapResponse)
            res = parseCapsResult(resultList)
        } yield res
    }

    private def parseCapResponse(json: JSONObject) = {
        val cmd = json.getString("cmd")
        if (json.has("error")) {
            cmd -> Left(OM.readValue(json.getJSONObject("error").toString, classOf[ReolinkCmdResponseError]))
        } else {
            cmd -> Right(json.getJSONObject("value"))
        }
    }

    private def parseCapsResult(resultList: List[(String, Either[ReolinkCmdResponseError, JSONObject])]): (ReolinkCapabilities, ReolinkState) = {
        resultList.foldLeft((ReolinkCapabilities.defaultCapabilities, ReolinkCapabilities.defautlState))((a, b) => {
            parseCapabilities(a._1, a._2, b._1, b._2)
        })
    }

    private def parseCapabilities(caps: ReolinkCapabilities, state: ReolinkState, cmd: String, result: Either[ReolinkCmdResponseError, JSONObject]): (ReolinkCapabilities, ReolinkState) = {
        cmd match {
            case "GetIsp" => Try(parseGetIsp(caps, state, result)).toOption.getOrElse((caps, state))
            case "GetIrLights" => Try(parseGetIrLights(caps, state, result)).toOption.getOrElse((caps, state))
            case "GetZoomFocus" => Try(parseGetZoomFocus(caps, state, result)).toOption.getOrElse((caps, state))
            case "GetAlarm" => Try(parseGetAlarm(caps, state, result)).toOption.getOrElse((caps, state))
            case _ => (caps, state)
        }
    }

    private def parseGetIsp(caps: ReolinkCapabilities, state: ReolinkState, result: Either[ReolinkCmdResponseError, JSONObject]): (ReolinkCapabilities, ReolinkState) = {
        result match {
            case Left(_) => (caps, state)
            case Right(json) =>
                val isp = json.getJSONObject("Isp")
                val nv = if (isp.has("dayNight")) {
                    isp.getString("dayNight") match {
                        case "Auto" => Option(NightVisionMode.Auto)
                        case "Black&White" => Option(NightVisionMode.ForceOn)
                        case "Color" => Option(NightVisionMode.ForceOff)
                        case _ => None
                    }
                } else None
                (caps.copy(nightVision = nv.isDefined), state.copy(nightVision = nv))
        }
    }

    private def parseGetIrLights(caps: ReolinkCapabilities, state: ReolinkState, result: Either[ReolinkCmdResponseError, JSONObject]): (ReolinkCapabilities, ReolinkState) = {
        result match {
            case Left(_) => (caps, state)
            case Right(json) =>
                val irlights = json.getJSONObject("IrLights")
                val nv = if (irlights.has("state")) {
                    irlights.getString("state") match {
                        case "Auto" => Option(true)
                        case "Off" => Option(false)
                        case _ => None
                    }
                } else None
                (caps.copy(irlights = nv.isDefined), state.copy(irlights = nv))
        }
    }

    private def parseGetZoomFocus(caps: ReolinkCapabilities, state: ReolinkState, result: Either[ReolinkCmdResponseError, JSONObject]): (ReolinkCapabilities, ReolinkState) = {
        result match {
            case Left(_) => (caps, state)
            case Right(json) =>
                val zoomFocus = json.getJSONObject("ZoomFocus")
                val nv = if (zoomFocus.has("zoom")) {
                    val rawPos = zoomFocus.getJSONObject("zoom").getInt("pos")
                    // transform scale from [0, 34] to [0, 100]
                    val scaledLevel = scala.math.round(rawPos.toFloat / 34.0f * 100.0f)
                    Some(scaledLevel)
                } else None
                (caps.copy(ptzZoom = nv.isDefined), state.copy(zoomAbsLevel = nv))
        }
    }

    private def parseGetAlarm(caps: ReolinkCapabilities, state: ReolinkState, result: Either[ReolinkCmdResponseError, JSONObject]): (ReolinkCapabilities, ReolinkState) = {
        result match {
            case Left(_) => (caps, state)
            case Right(json) =>
                val alarm = json.getJSONObject("Alarm")
                val params = OM.readValue(alarm.toString, classOf[SetAlarmCommandParams])
                val first = params.sens.sortBy(_.id).headOption
                // transform sensitivity scale from [50, 1] to [0, 100]
                val nv = first.map(_.sensitivity).map(_ - 1).map(_.toFloat / 49.0f * 100.0f).map(scala.math.round).map(100 - _)
                (caps.copy(motionSens = true), state.copy(motionSens = nv))
        }
    }

    def runGetCommands(host: ReolinkHost, cmd: List[ReolinkCmd])
                      (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String] = {
        reqPost(host, None, OM.writeValueAsString(cmd))
    }
}
