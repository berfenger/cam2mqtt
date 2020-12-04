package net.bfgnet.cam2mqtt.onvif

import java.time.ZoneId
import java.util.{Date, UUID}

import net.bfgnet.cam2mqtt.onvif.OnvifAuth.OnvifSign
import net.bfgnet.cam2mqtt.utils.DateTimeUtils
import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.digest.DigestUtils

object OnvifAuth {

    case class OnvifSign(token: String, username: String, passwordDigest: String, nonce: String, created: String)

    private val TMPL = """<wsse:Security soap:mustUnderstand="true" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                         |    xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                         |    <wsse:UsernameToken wsu:Id="UsernameToken-{UsernameToken}">
                         |    <wsse:Username>{Username}</wsse:Username>
                         |    <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">{PasswordDigest}</wsse:Password>
                         |    <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">{Nonce}</wsse:Nonce>
                         |    <wsu:Created>{Created}</wsu:Created>
                         |    </wsse:UsernameToken>
                         |    </wsse:Security>""".stripMargin
}

trait OnvifAuth {

    def auth(username: String, password: String): OnvifSign = {

        val raw_nonce = newNonce()
        val _nonce = base64(raw_nonce)

        val ts = now()

        val pwdDig = base64(sha1(raw_nonce ++ ts.getBytes("utf-8") ++ password.getBytes("utf-8")))

        OnvifSign(UUID.randomUUID().toString, username, pwdDig, _nonce, ts)
    }

    private def base64(d: Array[Byte]) = Base64.encodeBase64String(d)

    private def sha1(d: Array[Byte]) = DigestUtils.digest(DigestUtils.getSha1Digest, d)

    private def newNonce() = UUID.randomUUID().toString.getBytes("utf-8")

    private def now() = DateTimeUtils.dateFormatter("yyyy-MM-dd'T'HH:mm:ss.000Z", ZoneId.systemDefault()).format(new Date())

    implicit class OnvifSignExt(sign: OnvifSign) {
        def appliedToXML(): String = {
            OnvifAuth.TMPL
                    .replace("{UsernameToken}", sign.token)
                    .replace("{Username}", sign.username)
                    .replace("{PasswordDigest}", sign.passwordDigest)
                    .replace("{Nonce}", sign.nonce)
                    .replace("{Created}", sign.created)
        }
    }
}
