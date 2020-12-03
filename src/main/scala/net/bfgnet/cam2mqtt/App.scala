package net.bfgnet.cam2mqtt

import akka.actor.typed.ActorSystem
import net.bfgnet.cam2mqtt.config.ConfigManager
import net.bfgnet.cam2mqtt.http.HttpRoutes
import net.bfgnet.cam2mqtt.system.{O2MActorSystem, O2MCommand}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, blocking}
import scala.language.postfixOps

object App {

    def main(args: Array[String]): Unit = {

        // load config file
        ConfigManager.loadFile()

        implicit val _as: ActorSystem[O2MCommand] = O2MActorSystem.start()
        implicit val _ec: ExecutionContext = _as.executionContext

        prepareShutdown()

        HttpRoutes.serve()
    }

    private def prepareShutdown()(implicit _as: ActorSystem[_], _ec: ExecutionContext) = {
        Runtime.getRuntime.addShutdownHook(new Thread() {
            override def run(): Unit = {
                O2MActorSystem.stop()
                blocking {
                    Await.result(_as.whenTerminated, 10 seconds)
                }
            }
        });
    }

    def env(variable: String): Option[String] =
        Option(System.getenv(variable)).filter(_.length > 0)
}
