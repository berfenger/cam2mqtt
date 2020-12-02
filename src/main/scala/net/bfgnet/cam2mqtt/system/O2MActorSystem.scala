package net.bfgnet.cam2mqtt.system

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Terminated}
import net.bfgnet.cam2mqtt.camera.{CameraMan, CameraManProtocol => cman}
import net.bfgnet.cam2mqtt.config.ConfigManager
import net.bfgnet.cam2mqtt.mqtt.MqttSystem
import net.bfgnet.cam2mqtt.mqtt.{MqttProtocol => mqtt}

sealed trait O2MCommand

case class WrappedCameraManCmd(cmd: cman.CameraManCmd) extends O2MCommand

case class WrappedMqttCmd(cmd: mqtt.MqttCmd) extends O2MCommand

case object Terminate extends O2MCommand

object O2MActorSystem {

    private var actorSystem: Option[ActorSystem[O2MCommand]] = None

    def start(): ActorSystem[O2MCommand] = synchronized {
        val _as: ActorSystem[O2MCommand] = ActorSystem(apply(), "cam2mqtt")
        actorSystem = Option(_as)
        _as
    }

    def stop() = {
        actorSystem.foreach(_ ! Terminate)
    }

    def sendToCameraMan(cmd: cman.CameraManCmd) =
        actorSystem.foreach(_ ! WrappedCameraManCmd(cmd))

    def sendToMQTT(cmd: mqtt.MqttCmd) =
        actorSystem.foreach(_ ! WrappedMqttCmd(cmd))

    private def apply(): Behavior[O2MCommand] = {
        Behaviors.setup { context =>
            val cameraManRef = context.spawn(CameraMan.apply(ConfigManager.cameras), "cameraman")
            val mqttRef = context.spawn(MqttSystem.apply(ConfigManager.mqtt), "mqtt")
            context.watch(cameraManRef)
            context.watch(mqttRef)
            Behaviors.receiveMessagePartial[O2MCommand] {
                case WrappedCameraManCmd(cmd) =>
                    cameraManRef ! cmd
                    Behaviors.same
                case WrappedMqttCmd(cmd) =>
                    mqttRef ! cmd
                    Behaviors.same
                case Terminate =>
                    cameraManRef ! cman.Terminate
                    mqttRef ! mqtt.Terminate
                    finishing(List(cameraManRef, mqttRef))
            }
        }
    }

    private def finishing(monitored: List[ActorRef[_]]): Behavior[O2MCommand] =
        Behaviors.receiveSignal {
            case (_, Terminated(a)) =>
                println(s"ROOT SE ME MUERE ${a}")
                val remaining = monitored.filterNot(_ == a)
                if (remaining.nonEmpty)
                    finishing(remaining)
                else {
                    println("Root TODOs hijos muertos")
                    Behaviors.stopped
                }
        }

}
