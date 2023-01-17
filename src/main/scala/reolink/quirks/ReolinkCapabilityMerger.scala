package net.bfgnet.cam2mqtt
package reolink.quirks

import camera.CameraConfig.ReolinkCameraModuleConfig
import camera.modules.reolink.{AiDetectionMode, ReolinkCapabilities, ReolinkState}

object ReolinkCapabilityMerger {

    import ReolinkQuirkModel._

    def merge(config: ReolinkCameraModuleConfig, caps: ReolinkCapabilities, state: ReolinkState): QuirkData = {
        // find applicable quirks for camera
        val quirks = caps.model.flatMap { m =>
            ReolinkQuirks.QUIRKS.find(k => m.model.matches(k._1)).map(_._2)
        }.getOrElse(Nil)
        // build final quirk list
        val fns = List[Quirk](aiDetection, alarmSirenDetection) ++ quirks
        // apply quirks
        fns.foldLeft((config, caps, state))(applyQuirk)
    }

    private def applyQuirk(qd: QuirkData, quirk: Quirk): QuirkData =
        quirk.apply(qd._1, qd._2, qd._3)

    private def aiDetection(config: ReolinkCameraModuleConfig, caps: ReolinkCapabilities, state: ReolinkState): (ReolinkCameraModuleConfig, ReolinkCapabilities, ReolinkState) = {
        val aiDetectionModeMod = if (caps.aiDetection) {
            config.aiDetectionMode.getOrElse(state.aiDetectionMode)
        } else AiDetectionMode.UnSupported
        (config, caps, state.copy(aiDetectionMode = aiDetectionModeMod))
    }

    private def alarmSirenDetection(config: ReolinkCameraModuleConfig, caps: ReolinkCapabilities, state: ReolinkState): (ReolinkCameraModuleConfig, ReolinkCapabilities, ReolinkState) = {
        // I suppose that every camera that has a spotlight, also has an alarm siren ¯\_(ツ)_/¯
        val hasAlarm = caps.spotlight
        (config, caps.copy(alarm = hasAlarm), state)
    }

}
