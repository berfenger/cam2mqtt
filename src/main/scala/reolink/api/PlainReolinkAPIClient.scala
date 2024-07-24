package net.bfgnet.cam2mqtt
package reolink.api

import reolink.ReolinkHost

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.{ExecutionContext, Future}

case class PlainLoginState() extends ReolinkApiLoginState

trait PlainReolinkAPIClient extends ReolinkAPIClient[PlainLoginState] with ReolinkHttpUtils {

    override def login(host: ReolinkHost)
                      (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[PlainLoginState] =
        Future.successful(PlainLoginState())

    override def reqPost(state: PlainLoginState, host: ReolinkHost, cmd: Option[String], body: String)
                        (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String] = {
        val http = Http(_as)
        val ent = HttpEntity.apply(ContentType.apply(MediaTypes.`application/json`), body)
        val _req = HttpRequest(uri = cmdUrl(host, cmd), method = HttpMethods.POST).withEntity(ent)
        http.singleRequest(_req, connectionContext = insecureHttpsContext).flatMap { r =>
            if (r.status.isRedirection())
                throw new Exception("could not query camera. HTTP port may be disabled.")
            else if (r.status.isSuccess())
                Unmarshal(r.entity.withSizeLimit(MAX_BODY_SIZE)).to[String]
            else
                throw new Exception(s"could not query camera (HTTP ${r.status.intValue()} ${host}")
        }
    }

    private def cmdUrl(host: ReolinkHost, cmd: Option[String]) = {
        val params = List("user" -> host.username, "password" -> host.password) ++ cmd.map(c => List("cmd" -> c)).getOrElse(Nil)
        val scheme = if (host.ssl) "https" else "http"
        Uri.apply(s"$scheme://${host.host}:${host.port}/cgi-bin/api.cgi?${queryParams(params)}")
    }
}
