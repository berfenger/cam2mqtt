package net.bfgnet.cam2mqtt.http.onvif_callback

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import net.bfgnet.cam2mqtt.camera.CameraManProtocol.RouteCameraCommand
import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraModuleMessage
import net.bfgnet.cam2mqtt.camera.modules.onvif.OnvifModule
import net.bfgnet.cam2mqtt.system.O2MActorSystem

import scala.concurrent.ExecutionContext

object OnvifCallbackRoutes {

    private val publishEvent = path("camera" / Segment) { cameraId =>
        post {
            entity(as[String]) { body =>
                O2MActorSystem.sendToCameraMan(RouteCameraCommand(cameraId, CameraModuleMessage(cameraId, OnvifModule.moduleId, body)))
                complete("OK")
            }
        }
    }

    def routes()(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Route = {
        pathPrefix("onvif" / "webhook") {
            publishEvent
        }
    }

}
