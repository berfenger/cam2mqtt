package net.bfgnet.cam2mqtt
package reolink.api

import reolink.{LoginCommand, LoginCommandDigest, ReolinkCmd, ReolinkHost}

import org.apache.commons.codec.binary.Base64
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.codehaus.jettison.json.JSONObject

import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

case class EncryptedLoginState(token: String, key: SecretKeySpec, iv: IvParameterSpec, expiryTimestamp: Long) extends ReolinkApiLoginState

case class PreLoginDigest(Realm: String, Method: String, Nonce: String, Nc: String, Qop: String)

trait EncryptedReolinkAPIClient extends ReolinkAPIClient[EncryptedLoginState] with ReolinkHttpUtils with ReolinkCryptoUtils {

    override def login(host: ReolinkHost)
                      (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[EncryptedLoginState] = {
        for {
            (headers, _) <- reqPostClear(host, loginUrl(host), OM.writeValueAsString(List(ReolinkCmd("Login", 0, LoginCommand(1, null)))))
            prelogin = parsePreLoginHeaders(headers)
            pr <- doLogin(host, prelogin)
        } yield pr
    }

    override def reqPost(state: EncryptedLoginState, host: ReolinkHost, cmd: Option[String], body: String)
                        (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String] = {
        val http = Http(_as)
        val ent = HttpEntity.apply(ContentType.apply(MediaTypes.`application/json`), Base64.encodeBase64String(encryptData(state, body)))
        val connHeader = RawHeader.apply("Connection", "close")
        val _req = HttpRequest(uri = cmdUrl(state, host, cmd), method = HttpMethods.POST).withEntity(ent).withHeaders(connHeader)
        http.singleRequest(_req, connectionContext = insecureHttpsContext).flatMap { r =>
            if (r.status.isRedirection())
                throw new Exception("could not query camera. HTTP port may be disabled.")
            else if (r.status.isSuccess())
                Unmarshal(r.entity.withSizeLimit(MAX_BODY_SIZE)).to[String].map {
                    decryptDataToString(state.key, state.iv, _)
                }
            else
                throw new Exception(s"could not query camera (HTTP ${r.status.intValue()} ${host}")
        }
    }

    private def cmdUrl(state: EncryptedLoginState, host: ReolinkHost, cmd: Option[String]) = {
        val cmdQP = cmd.map(c => List("cmd" -> c)).getOrElse(Nil)
        val qp = queryParams(List("checkNum" -> Random.nextInt(100).toString) ++ cmdQP)
        val params = queryParams("token" -> state.token, "encrypt" -> Base64.encodeBase64String(encryptData(state, qp)))
        val scheme = if (host.ssl) "https" else "http"
        Uri.apply(s"$scheme://${host.host}:${host.port}/cgi-bin/api.cgi?${params}")
    }

    private def loginUrl(host: ReolinkHost) = {
        val scheme = if (host.ssl) "https" else "http"
        Uri.apply(s"$scheme://${host.host}:${host.port}/cgi-bin/api.cgi?cmd=Login")
    }

    private def loginDigest(host: ReolinkHost, preLoginDigest: PreLoginDigest): (SecretKeySpec, LoginCommandDigest) = {
        val cnonce = generateNonce(48)
        val uri = "cgi-bin/api.cgi?cmd=Login"
        val hash1 = md5(s"${host.username}:${preLoginDigest.Realm}:${host.password}")
        val hash2 = md5(s"${preLoginDigest.Method}:$uri")
        val response = md5(s"${hex(hash1)}:${preLoginDigest.Nonce}:${preLoginDigest.Nc}:$cnonce:${preLoginDigest.Qop}:${hex(hash2)}")

        val keyHash = hex(md5(s"${preLoginDigest.Nonce}-${host.password}-$cnonce")).toUpperCase()
        val aesKey = new SecretKeySpec(keyHash.substring(0, 16).getBytes("utf-8"), "AES")

        val digest = LoginCommandDigest(
            host.username, preLoginDigest.Realm, preLoginDigest.Method, uri, preLoginDigest.Nonce,
            preLoginDigest.Nc, cnonce, preLoginDigest.Qop, hex(response)
        )
        aesKey -> digest
    }

    private def doLogin(host: ReolinkHost, pre: PreLoginDigest)
                       (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[EncryptedLoginState] = {
        val (key, digest) = loginDigest(host, pre)
        reqPostClear(host, loginUrl(host), OM.writeValueAsString(List(ReolinkCmd("Login", 0, LoginCommand(1, digest))))).map(_._2).map { r =>
            val ivOpt = findIVByJSONCompliance(key, r, List(staticIV, () => webappIV(host, digest)))
            val iv = ivOpt.getOrElse(throw new Exception("could not find a suitable IV for this camera. Cannot use encrypted API"))
            val resultStr = decryptDataToString(key, iv, r)
            val json = new JSONObject(axeArray(resultStr))
            val tokenObj = json.getJSONObject("value").getJSONObject("Token")
            val token = tokenObj.getString("name")
            val leaseTime = tokenObj.getLong("leaseTime")
            EncryptedLoginState(token, key, iv, System.currentTimeMillis() + leaseTime * 1000 - 1000)
        }
    }
}
