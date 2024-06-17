package net.bfgnet.cam2mqtt
package reolink

import java.time.{ZoneId, ZoneOffset}
import java.util.{Calendar, GregorianCalendar, TimeZone}

import org.apache.pekko.actor.{ClassicActorSystemProvider, Scheduler}
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonInclude}
import camera.CameraActionProtocol.NightVisionMode
import org.codehaus.jettison.json.JSONObject

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

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

// V10 ScheduleTable
case class ScheduleTable(enable: Int, table: String) {
    def enabled(): ScheduleTable = this.copy(enable = 1)
    def disabled(): ScheduleTable = this.copy(enable = 0)
    def enabled(isEnabled: Boolean): ScheduleTable = if (isEnabled) enabled() else disabled()
    def withFullMotion(): ScheduleTable = this.copy(table = (0 until 168).map(_ => "1").mkString)
    def withNoMotion(): ScheduleTable = this.copy(table = (0 until 168).map(_ => "0").mkString)
}
// SetFtp
@JsonIgnoreProperties(ignoreUnknown = true)
case class SetFtpCommandParams(schedule: ScheduleTable)

case class SetFtpCommand(Ftp: SetFtpCommandParams) extends CommandParams

// SetFtpV20
@JsonIgnoreProperties(ignoreUnknown = true)
case class SetFtpV20CommandParams(enable: Int)

case class SetFtpV20Command(Ftp: SetFtpV20CommandParams) extends CommandParams

// SetRec
@JsonIgnoreProperties(ignoreUnknown = true)
case class SetRecCommandParams(schedule: ScheduleTable)

case class SetRecCommand(Rec: SetRecCommandParams) extends CommandParams

// SetRecV20
@JsonIgnoreProperties(ignoreUnknown = true)
case class SetRecV20CommandParams(enable: Int)

case class SetRecV20Command(Rec: SetRecV20CommandParams) extends CommandParams

// GetAiState
@JsonIgnoreProperties(ignoreUnknown = true)
case class GetAiObjectState(alarm_state: Int, support: Int) {
    def isSupported = support == 1
    def isDetected = alarm_state == 1
}

// SetWhiteLed
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
case class SetWhiteLedCommandParams(state: Integer, channel: Int, mode: Integer, bright: Integer)

case class SetWhiteLedCommand(WhiteLed: SetWhiteLedCommandParams) extends CommandParams

// AudioAlarmPlay
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
case class AudioAlarmPlayCommand(alarm_mode: String, channel: Int, times: Integer, manual_switch: Integer) extends CommandParams

// SetAudioCfg
@JsonIgnoreProperties(ignoreUnknown = true)
case class SetAudioCfgCommandParams(channel: Int, volume: Int)

case class SetAudioCfgCommand(AudioCfg: SetAudioCfgCommandParams) extends CommandParams

@JsonIgnoreProperties(ignoreUnknown = true)
case class GetAiStateParams(channel: Int, dog_cat: GetAiObjectState, face: GetAiObjectState, people: GetAiObjectState, vehicle: GetAiObjectState)

@JsonIgnoreProperties(ignoreUnknown = true)
case class GetAiStateCmdResponse(cmd: String, code: Int, value: GetAiStateParams)

// Common
case class Channel(channel: Int) extends CommandParams

case class ReolinkCmd(cmd: String, action: Int, param: CommandParams)

case class ReolinkCmdResponseValue(rspCode: Int) {
    def isOk: Boolean = rspCode == 200
}

case class ReolinkCmdResponseError(rspCode: Int, detail: String) {
    def errorString = s"$detail ($rspCode)"
}

case class ReolinkGetTimeResponseValue(rspCode: Int)

case class ReolinkCmdResponse(cmd: String, code: Int, value: ReolinkCmdResponseValue, error: Option[ReolinkCmdResponseError]) {
    def isOk: Boolean = Option(value).exists(_.isOk)

    def errorException: Option[Exception] = errorString.map(new Exception(_))

    def errorString: Option[String] = error.map(_.errorString)
}

trait ReolinkCommands extends ReolinkRequest {

    def setNightVision(host: ReolinkHost, mode: NightVisionMode.Value)
                      (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val convMode = mode match {
            case NightVisionMode.ForceOn => "Black&White"
            case NightVisionMode.ForceOff => "Color"
            case NightVisionMode.Auto => "Auto"
        }
        val cmd = ReolinkCmd("SetIsp", 0, IspCommand(IspCommandParams(0, Option(convMode))))
        runCommand(host, cmd)
    }

    def setIrLights(host: ReolinkHost, mode: Boolean)
                   (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val convMode = if (mode) "Auto" else "Off"
        val cmd = ReolinkCmd("SetIrLights", 0, IrLightsCommand(IrLightsCommandParams(convMode)))
        runCommand(host, cmd)
    }

    def setZoom(host: ReolinkHost, level: Int)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        if (level < 0 || level > 100) throw new IllegalArgumentException("zoom out of bounds [0, 100]")
        // scale zoom between [0, 34]
        val scaledLevel = scala.math.round(level.toFloat / 100.0f * 34.0f)
        val cmd = ReolinkCmd("StartZoomFocus", 0, ZoomFocusCommand(ZoomFocusCommandParams(0, scaledLevel, "ZoomPos")))
        runCommand(host, cmd)
    }

    def setTime(host: ReolinkHost, timestamp: Long, timeZone: TimeZone)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {

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
                     (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {

        val timestamp = System.currentTimeMillis()
        val tz = TimeZone.getDefault

        setTime(host, timestamp, tz)
    }

    def getTime(host: ReolinkHost)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[GregorianCalendar] = {

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
                          (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[Option[ReolinkCmdResponse]] = {
        for {
            current <- getTime(host)
            diff = scala.math.abs(current.getTimeInMillis - System.currentTimeMillis())
            needUpd = diff > maxSkewMillis
            r <- if (needUpd) {
                for {
                    set <- setSystemTime(host)
                    newTimeR <- if (set.isOk) {
                        // add a delay for increased realiability
                        implicit val _sch: Scheduler = _as.classicSystem.scheduler
                        postpone(3.seconds) {
                            getTime(host).map { d =>
                                val diff = scala.math.abs(d.getTimeInMillis - System.currentTimeMillis())
                                if (diff < maxSkewMillis) {
                                    set
                                } else set.copy(value = ReolinkCmdResponseValue(500), error = Option(ReolinkCmdResponseError(-1, "time was not updated")))
                            }
                        }
                    } else Future.successful(set)
                } yield Option(newTimeR)
            } else Future.successful(None)
        } yield r
    }

    def getAlarmSens(host: ReolinkHost)
                    (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[JSONObject] = {
        val cmd = ReolinkCmd("GetAlarm", 1, GetAlarmCommand(GetAlarmCommandParams(0, "md")))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => new JSONObject(axeArray(r)).getJSONObject("value").getJSONObject("Alarm")
        }
    }

    def setAlarmSens(host: ReolinkHost, sens: Int)
                    (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
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

    def setFTPEnabled(host: ReolinkHost, enabled: Boolean)
                    (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val sched = ScheduleTable(-1, null).enabled(enabled).withFullMotion()
        val cmd = ReolinkCmd("SetFtp", 0, SetFtpCommand(SetFtpCommandParams(sched)))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def setFTPV20Enabled(host: ReolinkHost, enabled: Boolean)
                     (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val cmd = ReolinkCmd("SetFtpV20", 0, SetFtpV20Command(SetFtpV20CommandParams(if (enabled) 1 else 0)))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def setRecordEnabled(host: ReolinkHost, enabled: Boolean)
                     (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val sched = ScheduleTable(-1, null).enabled(enabled).withFullMotion()
        val cmd = ReolinkCmd("SetRec", 0, SetRecCommand(SetRecCommandParams(sched)))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def setRecordV20Enabled(host: ReolinkHost, enabled: Boolean)
                        (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val cmd = ReolinkCmd("SetRecV20", 0, SetRecV20Command(SetRecV20CommandParams(if (enabled) 1 else 0)))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def getAiState(host: ReolinkHost)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[Option[GetAiStateCmdResponse]] = {
        val cmd = ReolinkCmd("GetAiState", 0, Channel(0))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map { r =>
            val resp = OM.readValue(axeArray(r), classOf[GetAiStateCmdResponse])
            Option(resp).filter(_.code == 0).filter(_.cmd == "GetAiState").filter(_.value != null)
        }
    }

    def runCommand(host: ReolinkHost, cmd: ReolinkCmd)
                  (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def setWhiteLed(host: ReolinkHost, enabled: Option[Boolean] = None, brightness: Option[Int] = None)
                           (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val p = SetWhiteLedCommandParams(enabled.map(v => if (v) 1 else 0).map(_.asInstanceOf[Integer]).orNull, 0, null,
            brightness.filter(v => v >= 0 && v <= 100).map(_.asInstanceOf[Integer]).orNull)
        val cmd = ReolinkCmd("SetWhiteLed", 0, SetWhiteLedCommand(p))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def setAudioCfg(host: ReolinkHost, volume: Int)
                   (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val p = SetAudioCfgCommandParams(0, volume)
        val cmd = ReolinkCmd("SetAudioCfg", 0, SetAudioCfgCommand(p))
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    def setAudioAlarmPlay(host: ReolinkHost, play: Boolean, times: Option[Int])
                   (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[ReolinkCmdResponse] = {
        val params = times match {
            case Some(times) if play => AudioAlarmPlayCommand("times", 0, times, null)
            case _ => AudioAlarmPlayCommand("manul", 0, null, if (play) 1 else 0)
        }
        val cmd = ReolinkCmd("AudioAlarmPlay", 0, params)
        reqPost(host, Option(cmd.cmd), OM.writeValueAsString(List(cmd))).map {
            r => parseCommandResponse(r)
        }
    }

    private def postpone[T](duration: FiniteDuration)(code: => Future[T])(implicit _ec: ExecutionContext, _sch: Scheduler): Future[T] = {
        val p = Promise[T]()
        _sch.scheduleOnce(duration) {
            code.onComplete(p.tryComplete)
        }
        p.future
    }
}
