package net.bfgnet.cam2mqtt
package reolink.api

import reolink.ReolinkHost

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}

import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, SSLEngine, X509TrustManager}
import scala.concurrent.{ExecutionContext, Future}

object NoCheckX509TrustManager extends X509TrustManager {
    override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()

    override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()

    override def getAcceptedIssuers: Array[X509Certificate] = Array[X509Certificate]()
}

trait ReolinkHttpUtils {

    protected val insecureHttpsContext: HttpsConnectionContext =
        ConnectionContext.httpsClient(createInsecureSslEngine _)
    protected val MAX_BODY_SIZE: Int = 500 * 1024

    protected val OM = new ObjectMapper()
    OM.registerModule(DefaultScalaModule)
    OM.setSerializationInclusion(Include.NON_NULL)

    protected def reqPostClear(host: ReolinkHost, uri: Uri, body: String)
                              (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[(Seq[(String, String)], String)] = {
        val http = Http(_as)
        val ent = HttpEntity.apply(ContentType.apply(MediaTypes.`application/json`), body)
        val connHeader = RawHeader.apply("Connection", "close")
        val _req = HttpRequest(uri = uri, method = HttpMethods.POST).withEntity(ent).withHeaders(connHeader)
        http.singleRequest(_req, connectionContext = insecureHttpsContext).flatMap { r =>
            val headers = r.headers.map(h => h.name() -> h.value())
            if (r.status.isRedirection())
                throw new Exception("could not query camera. HTTP port may be disabled.")
            else if (r.status.isSuccess())
                Unmarshal(r.entity.withSizeLimit(MAX_BODY_SIZE)).to[String].map { r => headers -> r }
            else
                throw new Exception(s"could not query camera (HTTP ${r.status.intValue()} ${host}")
        }
    }

    protected def parsePreLoginHeaders(headers: Seq[(String, String)]): PreLoginDigest = {
        val www = headers.find(_._1.toLowerCase == "www-authenticate").map(_._2)
            .getOrElse(throw new Exception("could not find login auth header"))
        www.split(" ").toList match {
            case "Digest" :: data :: Nil =>
                val fields = data.split(",").toList.map { pair =>
                    pair.split("=").toList match {
                        case name :: value :: Nil =>
                            name -> trimQuotes(value)
                        case _ => throw new Exception("could not parse field on WWW-Authenticate header")
                    }
                }
                val ff = findWWWHeaderField(fields) _
                PreLoginDigest(Realm = ff("realm"), Method = "POST", Nonce = ff("nonce"), Nc = ff("nc"), Qop = ff("qop"))
            case _ => throw new Exception("could not parse WWW-Authenticate header")
        }
    }

    private def findWWWHeaderField(header: List[(String, String)])(field: String): String = {
        header.find(_._1 == field).map(_._2).getOrElse(throw new Exception(s"could find field $field on WWW-Authenticate header"))
    }

    protected def queryParams(params: List[(String, String)]): String = params.map { case (k, v) =>
        s"$k=${urlEncode(v)}"
    }.mkString("&")

    protected def queryParams(params: (String, String)*): String = queryParams(params.toList)

    private def urlEncode(str: String): String = URLEncoder.encode(str, "utf-8")

    private def trimQuotes(str: String): String = {
        val trimmed = str.trim
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
    }

    protected def axeArray(str: String): String = {
        val trimmed = str.trim
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
    }

    private def createInsecureSslEngine(host: String, port: Int): SSLEngine = {
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
