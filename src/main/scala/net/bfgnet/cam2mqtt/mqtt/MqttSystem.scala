package net.bfgnet.cam2mqtt.mqtt

import akka.Done
import akka.actor.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{Behavior, SupervisorStrategy}
import akka.stream.alpakka.mqtt.scaladsl.MqttFlow
import akka.stream.alpakka.mqtt.{MqttConnectionSettings, MqttMessage, MqttQoS, MqttSubscriptions}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorAttributes, OverflowStrategy, Supervision}
import javax.net.ssl.SSLContext
import net.bfgnet.cam2mqtt.camera.CameraProtocol.{CameraAvailableEvent, CameraEvent}
import net.bfgnet.cam2mqtt.camera.modules.CameraModules
import net.bfgnet.cam2mqtt.config.{ConfigManager, MqttConfig}
import net.bfgnet.cam2mqtt.eventbus.CameraEventBus
import net.bfgnet.cam2mqtt.mqtt.MqttProtocol._
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

case class MqttBaseTopicProvider(base: String)

object MqttProtocol {

    sealed trait MqttCmd

    case class CameraEventReceived(ev: CameraEvent) extends MqttCmd

    case object MQTTConnected extends MqttCmd

    case class MQTTDisconnected(reason: Throwable) extends MqttCmd

    case object StreamCompleteInmediate extends MqttCmd

    case class TerminatedWithError(err: Throwable) extends MqttCmd

    case object TerminatedOk extends MqttCmd

    case object Terminate extends MqttCmd

}

object MqttSystem extends ActorContextImplicits {

    def apply(cfg: MqttConfig): Behavior[MqttCmd] = {
        val baseTopic = cfg.base_name.getOrElse("cam2mqtt")
        Behaviors.supervise[MqttCmd] {
            Behaviors.setup { implicit context =>

                context.setLoggerName(MqttSystem.getClass)
                context.log.info(s"starting MQTT client...")

                val act = context.messageAdapter[CameraEvent](ev => CameraEventReceived(ev))

                val src = mqttStreamSource(baseTopic, connectionSettings(cfg))
                val ((mqttStream, subscribed), result) = src.run()
                subscribed.map { _ =>
                    context.self ! MQTTConnected
                }.recover {
                    case e: Throwable =>
                        context.self ! MQTTDisconnected(e)
                        throw e
                }
                mqttStream.watchCompletion().map { _ =>
                    context.self ! TerminatedOk
                }.recover {
                    case e => context.self ! TerminatedWithError(e)
                }

                Behaviors.receiveMessagePartial[MqttCmd] {
                    case MQTTConnected =>
                        context.log.info("mqtt client successfully connected")
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_MOTION)
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_AVAILABILITY)
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_OTHER)
                        connected(act.toClassic, mqttStream)
                    case MQTTDisconnected(err) =>
                        context.log.error("mqtt client connection error", err)
                        mqttStream.fail(err)
                        CameraEventBus.bus.unsubscribe(act.toClassic)
                        failing()
                    case Terminate =>
                        mqttStream.complete()
                        terminatingOk()
                }
            }
        }.onFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 4.seconds, maxBackoff = 1.minute, randomFactor = 0.2))
    }

    def mqttStreamSource(baseTopic: String, settings: MqttConnectionSettings) = {
        val inputTopic = s"$baseTopic/camera/+/command/#"

        val mqttFlow: Flow[MqttMessage, MqttMessage, Future[Done]] =
            MqttFlow.atMostOnce(
                settings,
                MqttSubscriptions(inputTopic, MqttQoS.AtLeastOnce),
                bufferSize = 8,
                MqttQoS.AtLeastOnce
            )
        val alwaysStop: Supervision.Decider = (_ => Supervision.Stop)
        val stratAlwaysStop = ActorAttributes.supervisionStrategy(alwaysStop)

        implicit val baseTopicProv = MqttBaseTopicProvider(baseTopic)

        Source
                .queue(200, OverflowStrategy.fail)
                .map { camEv =>
                    event2Mqtt(camEv)
                }
                .filter(_.isDefined)
                .map(_.get)
                .viaMat(mqttFlow)(Keep.both)
                .map { ev =>
                    Try {
                        MqttCommands.inputCommand(baseTopic, ev)
                    }
                }
                .toMat(Sink.ignore)(Keep.both)
                .withAttributes(stratAlwaysStop)
    }

    private def connected(eventBusListener: ActorRef, mqttStream: SourceQueueWithComplete[CameraEvent]): Behavior[MqttCmd] = {
        Behaviors.setup { implicit context =>
            Behaviors.receiveMessagePartial {
                case CameraEventReceived(ev) =>
                    mqttStream.offer(ev)
                    Behaviors.same
                case MQTTConnected =>
                    context.log.info("mqtt client successfully connected")
                    CameraEventBus.bus.subscribe(eventBusListener, CameraEventBus.TOPIC_MOTION)
                    CameraEventBus.bus.subscribe(eventBusListener, CameraEventBus.TOPIC_AVAILABILITY)
                    connected(eventBusListener, mqttStream)
                case MQTTDisconnected(err) =>
                    context.log.error("mqtt client connection error", err)
                    mqttStream.fail(err)
                    CameraEventBus.bus.unsubscribe(eventBusListener)
                    failing()
                case Terminate =>
                    CameraEventBus.bus.unsubscribe(eventBusListener)
                    //mqttStream.complete()
                    sendingOffline(mqttStream)
            }
        }
    }

    private def sendingOffline(mqttStream: SourceQueueWithComplete[CameraEvent]): Behavior[MqttCmd] =
        Behaviors.setup { context =>
            context.scheduleOnce(1.seconds, context.self, TerminatedOk)
            ConfigManager.cameras.map(_.cameraId).foreach { id =>
                mqttStream.offer(CameraAvailableEvent(id, available = false))
            }
            Behaviors.receiveMessagePartial {
                case TerminatedOk =>
                    mqttStream.complete()
                    terminatingOk()
                case TerminatedWithError(_) =>
                    Behaviors.stopped
                case _ =>
                    Behaviors.same
            }
        }

    private def terminatingOk(): Behavior[MqttCmd] =
        Behaviors.receiveMessagePartial {
            case TerminatedOk =>
                Behaviors.stopped
            case TerminatedWithError(_) =>
                Behaviors.stopped
            case _ =>
                Behaviors.same
        }

    private def failing(): Behavior[MqttCmd] =
        Behaviors.receiveMessagePartial {
            case TerminatedOk =>
                throw new Exception("stream failed")
            case TerminatedWithError(err) =>
                throw err
            case _ =>
                Behaviors.same
        }

    private def connectionSettings(cfg: MqttConfig): MqttConnectionSettings = {
        Option(MqttConnectionSettings(
            s"${if (cfg.ssl) "ssl" else "tcp"}://${cfg.host}:${cfg.port}",
            "cam2mqtt-client",
            new MemoryPersistence()
        ).withAuth(cfg.username, cfg.password).withAutomaticReconnect(true).withCleanSession(true)).map { v =>
            if (cfg.ssl) v.withSocketFactory(SSLContext.getDefault.getSocketFactory) else v
        }.get
    }

    private def event2Mqtt(ev: CameraEvent)(implicit _btp: MqttBaseTopicProvider): Option[MqttMessage] = (ev match {
        // transform some events?
        case ev => moduleEvent2Mqtt(ev)
    }).map(ev => ev.withTopic(s"${_btp.base}/${ev.topic}")) // prepend base topic

    private def moduleEvent2Mqtt(ev: CameraEvent)(implicit _btp: MqttBaseTopicProvider): Option[MqttMessage] =
        CameraModules.MQTT_MODULES.find(_.moduleId == ev.moduleId).flatMap(_.eventToMqttMessage(ev))
}
