package net.bfgnet.cam2mqtt
package reolink.api

import reolink.{LoginCommandDigest, ReolinkHost}

import org.apache.commons.codec.binary.{Base64, Hex}
import org.codehaus.jettison.json.{JSONArray, JSONObject}

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import scala.util.{Random, Try}

trait ReolinkCryptoUtils {

    private val IV_STATIC = "bcswebapp1234567".getBytes("utf-8")

    protected def staticIV(): IvParameterSpec =
        new IvParameterSpec(IV_STATIC)

    protected def webappIV(host: ReolinkHost, loginDigest: LoginCommandDigest): IvParameterSpec = {
        val ivHex = hex(md5(s"webapp-${loginDigest.Cnonce}-${host.password}-${loginDigest.Nonce}-${host.username}")).toUpperCase.substring(0, 16)
        new IvParameterSpec(ivHex.getBytes("utf-8"))
    }

    protected def findIVByJSONCompliance(key: SecretKeySpec, dataBase64: String, ivFns: List[() => IvParameterSpec]): Option[IvParameterSpec] = {
        ivFns.to(LazyList).map(_()).find { iv =>
            val decryptedStr = new String(decryptData(key, iv, dataBase64), "utf-8")
            Try(new JSONArray(decryptedStr)).isSuccess || Try(new JSONObject(decryptedStr)).isSuccess
        }
    }

    protected def encryptData(state: EncryptedLoginState, data: String): Array[Byte] =
        encryptData(state.key, state.iv, data)

    protected def encryptData(key: SecretKeySpec, iv: IvParameterSpec, data: String): Array[Byte] = {
        val cipherAes = Cipher.getInstance("AES/CFB/NoPadding")
        cipherAes.init(Cipher.ENCRYPT_MODE, key, iv)
        cipherAes.doFinal(data.getBytes("utf-8"))
    }

    protected def decryptDataToString(key: SecretKeySpec, iv: IvParameterSpec, dataBase64: String): String =
        new String(decryptData(key, iv, dataBase64), "utf-8")

    protected def decryptData(key: SecretKeySpec, iv: IvParameterSpec, dataBase64: String): Array[Byte] = {
        val cipherAes = Cipher.getInstance("AES/CFB/NoPadding")
        cipherAes.init(Cipher.DECRYPT_MODE, key, iv)
        cipherAes.doFinal(Base64.decodeBase64(dataBase64))
    }

    protected def generateNonce(length: Int): String = {
        val seed = "0123456789abcdef"
        (1 to length).map(_ => seed(Random.nextInt(seed.length))).mkString
    }

    protected def hex(buf: Array[Byte]) = Hex.encodeHex(buf).mkString.toLowerCase

    protected def md5(s: String) = {
        MessageDigest.getInstance("MD5").digest(s.getBytes)
    }
}
