package net.bfgnet.cam2mqtt
package utils

import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.TimeZone

object DateTimeUtils {
    def dateFormatter(format: String, tz: ZoneId = ZoneId.of("UTC")): SimpleDateFormat = {
        val TIME_FMT = new SimpleDateFormat(format)
        TIME_FMT.setTimeZone(TimeZone.getTimeZone(tz))
        TIME_FMT
    }
}
