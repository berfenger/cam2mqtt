package net.bfgnet.cam2mqtt

import java.io.File

import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.actor.typed.ActorSystem
import com.typesafe.sslconfig.util.ConfigLoader
import akka.actor.typed.scaladsl.adapter._
import net.bfgnet.cam2mqtt.camera.CameraMan
import net.bfgnet.cam2mqtt.camera.CameraManProtocol.CameraManCmd
import net.bfgnet.cam2mqtt.config.{ConfigManager, ConfigParser}
import net.bfgnet.cam2mqtt.http.HttpRoutes
import net.bfgnet.cam2mqtt.mqtt.{MqttSystem}
import net.bfgnet.cam2mqtt.system.{O2MActorSystem, O2MCommand}

import scala.concurrent.{Await, ExecutionContext, blocking}
import scala.concurrent.duration._
import scala.language.postfixOps

object App {

    def main(args: Array[String]): Unit = {

        // load config file
        ConfigManager.loadFile()

        implicit val _as: ActorSystem[O2MCommand] = O2MActorSystem.start()
        implicit val _ec: ExecutionContext = _as.executionContext

        prepareShutdown()

        println("Serving...")
        HttpRoutes.serve()
    }

    private def prepareShutdown()(implicit _as: ActorSystem[_], _ec: ExecutionContext) = {
        Runtime.getRuntime.addShutdownHook(new Thread() {
            override def run(): Unit = {
                println("Terminating...")
                O2MActorSystem.stop()
                //Thread.sleep(4000)
                /*val ft = _as.terminate()
                blocking {
                    Await.result(ft, 10 seconds)
                    println("Terminated")
                }*/
                //_as.terminate()
                blocking {
                    Await.result(_as.whenTerminated, 10 seconds)
                    println("Terminated")
                }
            }
        });
    }

    def env(variable: String): Option[String] =
        Option(System.getenv(variable)).filter(_.length > 0)
}
