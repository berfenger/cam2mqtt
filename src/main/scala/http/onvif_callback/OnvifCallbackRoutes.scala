package net.bfgnet.cam2mqtt
package http.onvif_callback

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import camera.CameraManProtocol.RouteCameraCommand
import camera.CameraProtocol.CameraModuleMessage
import camera.modules.onvif.OnvifModule
import system.O2MActorSystem

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
