package net.bfgnet.cam2mqtt
package mqtt.path

trait MqttPaths {
    protected def cameraPath(cameraId: String) =
        s"camera/$cameraId"

    protected def cameraEventModulePath(cameraId: String, moduleId: String) =
        s"${cameraPath(cameraId)}/event/$moduleId"

    protected def cameraStateModulePath(cameraId: String, moduleId: String) =
        s"${cameraPath(cameraId)}/state/$moduleId"
}
