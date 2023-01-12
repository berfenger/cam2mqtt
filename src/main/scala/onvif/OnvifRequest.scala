package net.bfgnet.cam2mqtt
package onvif

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.ExecutionContext

trait OnvifRequest {

    def req(host: String, port: Int,
            xml: String, headers: List[(String, String)] = Nil)(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        val http = Http(_as)
        val ent = HttpEntity.apply(ContentType.apply(MediaTypes.`application/soap+xml`, HttpCharsets.`UTF-8`), xml)
        val hs = headers.flatMap(p => header(p._1, p._2))
        val _req = HttpRequest(uri = s"http://$host:$port", method = HttpMethods.POST).withEntity(ent).withHeaders(hs)
        http.singleRequest(_req).flatMap { r =>
            Unmarshal(r.entity.withSizeLimit(10000)).to[String]
        }
    }

    private def header(key: String, value: String) =
        HttpHeader.parse(key, value) match {
            case ParsingResult.Ok(h, _) => Option(h)
            case _ => None
        }

}
