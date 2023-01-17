package net.bfgnet.cam2mqtt
package reolink.quirks

import camera.CameraConfig.ReolinkCameraModuleConfig
import camera.modules.reolink.{ReolinkCapabilities, ReolinkState}

import scala.language.implicitConversions

object ReolinkQuirks {

    import ReolinkQuirkModel._

    private val noAAS = List(disableAudio, disableAlarm, disableSpotlight)

    val QUIRKS = Map[String, List[Quirk]](
        "RLC-520A?" -> noAAS,
        "RLC-842A?" -> noAAS,
        "RLC-822A?" -> noAAS,
        "RLC-820A?" -> noAAS,
        "RLC-542W?A?" -> noAAS,
        "RLC-510W?A?" -> noAAS,
        "RLC-410W?A?" -> noAAS,
    )

    def changeAudio(value: Boolean)(config: ReolinkCameraModuleConfig, caps: ReolinkCapabilities, state: ReolinkState): QuirkData =
        (config, caps.copy(audio = caps.audio && value), state)

    def enableAudio: Quirk = changeAudio(true)

    def disableAudio: Quirk = changeAudio(false)

    def changeAlarm(value: Boolean)(config: ReolinkCameraModuleConfig, caps: ReolinkCapabilities, state: ReolinkState): QuirkData =
        (config, caps.copy(alarm = caps.alarm && value), state)

    def enableAlarm: Quirk = changeAlarm(true)

    def disableAlarm: Quirk = changeAlarm(false)

    def changeSpotlight(value: Boolean)(config: ReolinkCameraModuleConfig, caps: ReolinkCapabilities, state: ReolinkState): QuirkData =
        (config, caps.copy(spotlight = caps.spotlight && value), state)

    def enableSpotlight: Quirk = changeSpotlight(true)

    def disableSpotlight: Quirk = changeSpotlight(false)
}

object ReolinkQuirkModel {
    type QuirkData = (ReolinkCameraModuleConfig, ReolinkCapabilities, ReolinkState)
    type Quirk = QuirkData => QuirkData

    implicit def convertFn(fn: (ReolinkCameraModuleConfig, ReolinkCapabilities, ReolinkState) => QuirkData): QuirkData => QuirkData =
        (a: QuirkData) => fn(a._1, a._2, a._3)
}
