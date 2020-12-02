package net.bfgnet.cam2mqtt.camera.modules

import net.bfgnet.cam2mqtt.camera.modules.generic.GenericMqttCamModule
import net.bfgnet.cam2mqtt.camera.modules.onvif.OnvifModule
import net.bfgnet.cam2mqtt.camera.modules.reolink.ReolinkModule

object CameraModules {

    val MODULES: List[CameraModule] = List(
        OnvifModule, ReolinkModule
    )

    val MQTT_MODULES: List[MqttCameraModule] =
        MODULES.filter(_.isInstanceOf[MqttCameraModule]).map(_.asInstanceOf[MqttCameraModule]) :+ GenericMqttCamModule

}
