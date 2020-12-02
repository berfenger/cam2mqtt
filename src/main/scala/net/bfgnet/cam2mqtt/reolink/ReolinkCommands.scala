package net.bfgnet.cam2mqtt.reolink

import java.time.{ZoneId, ZoneOffset}
import java.util.{Calendar, GregorianCalendar, TimeZone}

import akka.actor.ClassicActorSystemProvider
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import net.bfgnet.cam2mqtt.camera.CameraActionProtocol.NightVisionMode
import org.codehaus.jettison.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}

sealed trait CommandParams

case class IspCommandParams(channel: Int, dayNight: Option[String])

case class IspCommand(Isp: IspCommandParams) extends CommandParams

case class IrLightsCommandParams(state: String)

case class IrLightsCommand(IrLights: IrLightsCommandParams) extends CommandParams

case class ZoomFocusCommandParams(channel: Int, pos: Int, op: String)

case class ZoomFocusCommand(ZoomFocus: ZoomFocusCommandParams) extends CommandParams

case class SetTimeCommandParams(year: Int, mon: Int, day: Int, hour: Int, min: Int, sec: Int, timeZone: Int)

case class SetTimeCommand(Time: SetTimeCommandParams) extends CommandParams

case class GetAlarmCommandParams(channel: Int, `type`: String)

case class GetAlarmCommand(Alarm: GetAlarmCommandParams) extends CommandParams

case class AlarmSens(id: Int, beginHour: Int, beginMin: Int, endHour: Int, endMin: Int, sensitivity: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
case class SetAlarmCommandParams(channel: Int, `type`: String, sens: List[AlarmSens])

case class SetAlarmCommand(Alarm: SetAlarmCommandParams) extends CommandParams

case class Channel(channel: Int) extends CommandParams

case class ReolinkCmd(cmd: String, action: Int, param: CommandParams)

case class ReolinkCmdResponseValue(rspCode: Int) {
    def isOk = rspCode == 200
}

case class ReolinkCmdResponseError(rspCode: Int, detail: String) {
    def errorString = s"$detail ($rspCode)"
}

case class ReolinkGetTimeResponseValue(rspCode: Int)

case class ReolinkCmdResponse(cmd: String, code: Int, value: ReolinkCmdResponseValue, error: Option[ReolinkCmdResponseError]) {
    def isOk = Option(value).exists(_.isOk)

    def errorException = errorString.map(new Exception(_))

    def errorString = error.map(_.errorString)
}

trait ReolinkCommands extends ReolinkRequest {

    def setNightVision(host: ReolinkHost, mode: NightVisionMode.Value)
                      (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        val convMode = mode match {
            case NightVisionMode.ForceOn => "Black&White"
            case NightVisionMode.ForceOff => "Color"
            case NightVisionMode.Auto => "Auto"
        }
        val cmd = ReolinkCmd("SetIsp", 0, IspCommand(IspCommandParams(0, Option(convMode))))
        runCommand(host, cmd)
    }

    def setIrLights(host: ReolinkHost, mode: Boolean)
                   (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        val convMode = mode match {
            case true => "Auto"
            case false => "Off"
        }
        val cmd = ReolinkCmd("SetIrLights", 0, IrLightsCommand(IrLightsCommandParams(convMode)))
        runCommand(host, cmd)
    }

    def setZoom(host: ReolinkHost, level: Int)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        if (level < 0 || level > 100) throw new IllegalArgumentException("zoom out of bounds [0, 100]")
        // scale zoom between [0, 34]
        val scaledLevel = scala.math.round(level.toFloat / 100.0f * 34.0f)
        val cmd = ReolinkCmd("StartZoomFocus", 0, ZoomFocusCommand(ZoomFocusCommandParams(0, scaledLevel, "ZoomPos")))
        runCommand(host, cmd)
    }

    def setTime(host: ReolinkHost, timestamp: Long, timeZone: TimeZone)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {

        val date = new GregorianCalendar()
        date.setTimeZone(timeZone)
        date.setTimeInMillis(timestamp)
        val year = date.get(Calendar.YEAR)
        val month = date.get(Calendar.MONTH) + 1
        val day = date.get(Calendar.DAY_OF_MONTH)
        val hour = date.get(Calendar.HOUR_OF_DAY)
        val min = date.get(Calendar.MINUTE)
        val sec = date.get(Calendar.SECOND)

        val offsetSecs = timeZone.getRawOffset / 1000

        val cmd = ReolinkCmd("SetTime", 0, SetTimeCommand(SetTimeCommandParams(year, month, day, hour, min, sec, -offsetSecs)))
        runCommand(host, cmd)
    }

    def setSystemTime(host: ReolinkHost)
                     (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {

        val timestamp = System.currentTimeMillis()
        val tz = TimeZone.getDefault

        setTime(host, timestamp, tz)
    }

    def getTime(host: ReolinkHost)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {

        val cmd = ReolinkCmd("GetTime", 1, null)
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map { r =>
            val time = new JSONObject(axeArray(r)).getJSONObject("value").getJSONObject("Time")
            val tzSecs = time.getInt("timeZone")
            val tzOffset = ZoneOffset.ofTotalSeconds(-tzSecs)
            val tz = TimeZone.getTimeZone(ZoneId.ofOffset("", tzOffset))

            val date = new GregorianCalendar()
            date.setTimeZone(tz)
            date.setTimeInMillis(0)
            date.set(Calendar.YEAR, time.getInt("year"))
            date.set(Calendar.MONTH, time.getInt("mon") - 1)
            date.set(Calendar.DAY_OF_MONTH, time.getInt("day"))
            date.set(Calendar.HOUR_OF_DAY, time.getInt("hour"))
            date.set(Calendar.MINUTE, time.getInt("min"))
            date.set(Calendar.SECOND, time.getInt("sec"))

            date
        }
    }

    def updateTimeIfNeeded(host: ReolinkHost, maxSkewMillis: Long)
                          (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        for {
            current <- getTime(host)
            diff = scala.math.abs(current.getTimeInMillis - System.currentTimeMillis())
            needUpd = diff > maxSkewMillis
            r <- if (needUpd) {
                for {
                    set <- setSystemTime(host)
                    newTimeR <- if (set.isOk) {
                        getTime(host).map { d =>
                            val diff = scala.math.abs(d.getTimeInMillis - System.currentTimeMillis())
                            if (diff < maxSkewMillis) {
                                set
                            } else set.copy(value = ReolinkCmdResponseValue(500), error = Option(ReolinkCmdResponseError(-1, "time was not updated")))
                        }
                    } else Future.successful(set)
                } yield Option(newTimeR)
            } else Future.successful(None)
        } yield r
    }

    def getAlarmSens(host: ReolinkHost)
                    (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        val cmd = ReolinkCmd("GetAlarm", 1, GetAlarmCommand(GetAlarmCommandParams(0, "md")))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => new JSONObject(axeArray(r)).getJSONObject("value").getJSONObject("Alarm")
        }
    }

    def setAlarmSens(host: ReolinkHost, sens: Int)
                    (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        if (sens < 0 || sens > 100) throw new IllegalArgumentException("zoom out of bounds [0, 100]")
        // scale sens between [1, 50]
        val scaledSens = scala.math.round(sens.toFloat / 100.0f * 49.0f)
        // sensitivity need to be inverted. 100 means no motion detection, 0 means full sensitivity
        val invertedSens = 50 - scaledSens
        getAlarmSens(host).flatMap { r =>
            val params = OM.readValue(r.toString, classOf[SetAlarmCommandParams])
            val updParams = params.copy(sens = params.sens.map(_.copy(sensitivity = invertedSens)))
            val cmd = ReolinkCmd("SetAlarm", 1, SetAlarmCommand(updParams))
            runCommand(host, cmd)
        }
    }

    def runCommand(host: ReolinkHost, cmd: ReolinkCmd)
                  (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }
}
