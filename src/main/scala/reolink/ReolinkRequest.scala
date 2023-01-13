package net.bfgnet.cam2mqtt
package reolink

import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{ConnectionContext, Http}
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import javax.net.ssl.{SSLContext, SSLEngine, X509TrustManager}

import scala.concurrent.ExecutionContext

case class ReolinkHost(host: String, port: Int, username: String, password: String, ssl: Boolean)

trait ReolinkRequest {

    val OM = new ObjectMapper()
    OM.registerModule(DefaultScalaModule)
    OM.setSerializationInclusion(Include.NON_NULL)

    protected val insecureHttpsContext = ConnectionContext.httpsClient(createInsecureSslEngine _)
    protected val MAX_BODY_SIZE = 500 * 1024

    def reqPost(host: ReolinkHost, cmd: Option[String], body: String)(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
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

    def reqGet(host: ReolinkHost, cmd: Option[String])(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext) = {
        val http = Http(_as)
        val _req = HttpRequest(uri = cmdUrl(host, cmd), method = HttpMethods.GET)
        http.singleRequest(_req, connectionContext = insecureHttpsContext).flatMap { r =>
            Unmarshal(r.entity.withSizeLimit(MAX_BODY_SIZE)).to[String]
        }
    }

    def cmdUrl(host: ReolinkHost, cmd: Option[String]) = {
        val params = List("user" -> host.username, "password" -> host.password) ++ cmd.map(c => List("cmd" -> c)).getOrElse(Nil)
        val scheme = if (host.ssl) "https" else "http"
        Uri.apply(s"$scheme://${host.host}:${host.port}/cgi-bin/api.cgi?${queryParams(params)}")
    }

    def axeArray(str: String) = {
        val trimmed = str.trim
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
    }

    def parseCommandResponse(resp: String): ReolinkCmdResponse =
        OM.readValue(axeArray(resp), classOf[ReolinkCmdResponse])

    def urlEncode(str: String) = URLEncoder.encode(str, "utf-8")

    def queryParams(params: List[(String, String)]): String = params.map { case (k, v) =>
        s"$k=${urlEncode(v)}"
    }.mkString("&")

    object NoCheckX509TrustManager extends X509TrustManager {
        override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()

        override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()

        override def getAcceptedIssuers: Array[X509Certificate] = Array[X509Certificate]()
    }

    def createInsecureSslEngine(host: String, port: Int): SSLEngine = {
        val context = SSLContext.getInstance("TLS")
        context.init(Array(), Array(NoCheckX509TrustManager), new SecureRandom())
        val engine = context.createSSLEngine(host, port)
        engine.setUseClientMode(true)
        engine.setSSLParameters({
            val params = engine.getSSLParameters
            params.setEndpointIdentificationAlgorithm(null)
            params
        })
        engine
    }

}
