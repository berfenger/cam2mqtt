package net.bfgnet.cam2mqtt.mqtt.path

trait CameraPath {

    protected def cameraPath(cameraId: String) =
        s"camera/$cameraId"

    protected def cameraEventModulePath(cameraId: String, moduleId: String) =
        s"${cameraPath(cameraId)}/event/$moduleId"
}

object CameraPath extends CameraPath
