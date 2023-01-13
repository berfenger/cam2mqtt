package net.bfgnet.cam2mqtt
package config

import java.io.{File, FileReader}
import java.nio.file.NoSuchFileException

import cats.syntax.either._
import io.circe.Decoder.Result
import io.circe.generic.auto._
import io.circe.generic.extras.Configuration
import io.circe.{yaml, _}
import camera.CameraConfig.CameraInfo
import camera.modules.CameraModules
import config.ConfigExceptions.{InvalidConfigFileFormat, ModuleNotFoundException}

case class MqttConfig(host: String, port: Int, username: String, password: String, ssl: Boolean, required: Option[Boolean], base_name: Option[String])

case class WebhookConfig(external_url: String)

case class Config(mqtt: MqttConfig, webhook: WebhookConfig, cameras: Map[String, Any])

case class CameraModule(module: String)

case class Camera(host: String, port: Int, username: String, password: String, modules: Map[String, Any])

object ConfigManager {

    private var _webhookConfig: Option[WebhookConfig] = None
    private var _mqttConfig: Option[MqttConfig] = None
    private var _camerasConfig: List[CameraInfo] = Nil

    def loadFile(): Unit = {
        val filename = env("CONFIG").getOrElse("./config.yml")
        loadFile(new File(filename))
    }

    def loadFile(file: File): Unit = {
        try {
            val rawConfig = ConfigParser.loadRawConfig(file)
            val cams = rawConfig.cameras.toList.map { case (k, v) =>
                val vs = v.asInstanceOf[Map[String, Any]]
                val c = CameraInfo(k, vs("host").asString, vs("username").asString, vs("password").asString, Nil)

                val mods = vs("modules").asMap.toList.map { case (m, mc) =>
                    val mod = CameraModules.MODULES.find(_.moduleId == m).getOrElse(throw new ModuleNotFoundException(m))
                    mod.loadConfiguration(mc.asMap)
                }
                c.copy(modules = mods)
            }
            _webhookConfig = Option(rawConfig.webhook)
            _mqttConfig = Option(rawConfig.mqtt)
            _camerasConfig = cams
        } catch {
            case ex: ModuleNotFoundException => throw ex
            case ex: Throwable => throw new InvalidConfigFileFormat(file, ex)
        }
    }

    private implicit class AnyExt(v: Any) {
        def asString = v.toString

        def asMap = v.asInstanceOf[Map[String, Any]]
    }

    private def env(variable: String): Option[String] =
        Option(System.getenv(variable)).filter(_.nonEmpty)

    def cameras: List[CameraInfo] = _camerasConfig
    def mqtt: MqttConfig = _mqttConfig.getOrElse(throw new RuntimeException("config is not loaded"))
    def webhookSubscription: WebhookConfig = _webhookConfig.getOrElse(throw new RuntimeException("config is not loaded"))
}

object ConfigExceptions {

    class ModuleNotFoundException(moduleId: String) extends Exception(s"could not find module ${moduleId}")
    class InvalidConfigFileFormat(file: File, cause: Throwable) extends Exception(s"could not parse config file ${file.getAbsolutePath}", cause)

}

object ConfigParser {

    def loadRawConfig(file: File) = {
        if (!file.exists()) throw new NoSuchFileException(s"could not find file ${file.getAbsolutePath}")

        // use snake case
        implicit val customConfig: Configuration = Configuration.default.withSnakeCaseMemberNames

        // parse yaml
        yaml.parser.parse(new FileReader(file))
                .leftMap(err => err: Error)
                .flatMap(_.as[Config])
                .valueOr(throw _)
    }

    private implicit val decodeMap: Decoder[Map[String, Any]] = new Decoder[Map[String, Any]] {
        override def apply(c: HCursor): Result[Map[String, Any]] = {
            val m: Map[String, Any] = if (c.value.isObject) {
                val obj = c.value.asObject.get
                obj.keys.map { k =>
                    val v = obj.apply(k).get
                    val p = if (v.isObject) {
                        apply(v.hcursor).getOrElse(null)
                    } else if (v.isString)
                        v.asString.get
                    else v.toString()
                    k -> p
                }.toList.toMap
            } else Map()
            Right(m)
        }
    }
}
