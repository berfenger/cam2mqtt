package net.bfgnet.cam2mqtt
package system

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, Terminated}
import camera.CameraManProtocol.CameraManCmd
import camera.{CameraMan, CameraManProtocol => cman}
import config.ConfigManager
import mqtt.MqttProtocol.{MQTTConnected, MqttCmd}
import mqtt.MqttSystem
import mqtt.{MqttProtocol => mqtt}

sealed trait O2MCommand

case class WrappedCameraManCmd(cmd: cman.CameraManCmd) extends O2MCommand

case class WrappedMqttCmd(cmd: mqtt.MqttCmd) extends O2MCommand

case class WrappedMqttConnectionCmd(cmd: mqtt.MqttCmd) extends O2MCommand

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
            val mqttRef = context.spawn(MqttSystem.apply(ConfigManager.mqtt, context.self), "mqtt")
            context.watch(mqttRef)
            if (ConfigManager.mqtt.required.contains(false)) {
                context.self !  WrappedMqttConnectionCmd(MQTTConnected)
            }
            running(None, mqttRef)
        }
    }

    private def running(cameraManActor: Option[ActorRef[CameraManCmd]], mqttActor: ActorRef[MqttCmd]): Behavior[O2MCommand] = {
        Behaviors.setup { context =>
            Behaviors.receiveMessagePartial[O2MCommand] {
                case WrappedCameraManCmd(cmd) =>
                    cameraManActor.foreach(_ ! cmd)
                    Behaviors.same
                case WrappedMqttCmd(cmd) =>
                    mqttActor ! cmd
                    Behaviors.same
                case WrappedMqttConnectionCmd(_ : MQTTConnected.type) if cameraManActor.isEmpty =>
                    // initialize cameraCam once MQTT is connected, not before
                    val cameraManRef = context.spawn(CameraMan.apply(ConfigManager.cameras), "cameraman")
                    context.watch(cameraManRef)
                    running(Some(cameraManRef), mqttActor)
                case Terminate =>
                    if (cameraManActor.toList.nonEmpty) {
                        cameraManActor.foreach(_ ! cman.Terminate)
                    } else {
                        context.stop(mqttActor)
                    }
                    finishing(cameraManActor.toList, Option(mqttActor))
            }
        }
    }

    private def finishing(monitored: List[ActorRef[_]], mqttActor: Option[ActorRef[MqttCmd]]): Behavior[O2MCommand] =
        Behaviors.receiveSignal {
            case (_, Terminated(a)) =>
                val monitored_rem = monitored.filterNot(_ == a)
                val mqttActor_rem = mqttActor.filter(_ != a)
                // when cameras have finished, terminate MQTT
                if (monitored_rem.isEmpty && mqttActor_rem.nonEmpty) {
                    mqttActor.foreach(_ ! mqtt.Terminate)
                }
                // stop when all children are stopped
                if (monitored_rem.nonEmpty || mqttActor_rem.nonEmpty)
                    finishing(monitored_rem, mqttActor_rem)
                else {
                    Behaviors.stopped
                }
        }

}
