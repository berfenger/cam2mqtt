package net.bfgnet.cam2mqtt
package http

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import http.onvif_callback.OnvifCallbackRoutes

import scala.concurrent.ExecutionContext

object HttpRoutes {

    def routes()(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Route = {
        OnvifCallbackRoutes.routes()
    }

    def serve()(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        Http().newServerAt("0.0.0.0", 8080).bindFlow(routes())
    }

}
