package net.bfgnet.cam2mqtt.http

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import net.bfgnet.cam2mqtt.http.onvif_callback.OnvifCallbackRoutes

import scala.concurrent.ExecutionContext

object HttpRoutes {

    def routes()(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Route = {
        OnvifCallbackRoutes.routes()
    }

    def serve()(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        Http().newServerAt("0.0.0.0", 8080).bindFlow(routes())
    }

}
