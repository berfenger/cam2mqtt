package net.bfgnet.cam2mqtt
package camera.modules

import camera.modules.generic.GenericMqttCamModule
import camera.modules.onvif.OnvifModule
import camera.modules.reolink.ReolinkModule

object CameraModules {

    val MODULES: List[CameraModule] = List(
        OnvifModule, ReolinkModule
    )

    val MQTT_MODULES: List[MqttCameraModule] =
        MODULES.filter(_.isInstanceOf[MqttCameraModule]).map(_.asInstanceOf[MqttCameraModule]) :+ GenericMqttCamModule

}
