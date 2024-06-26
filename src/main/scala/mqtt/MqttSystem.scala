package net.bfgnet.cam2mqtt
package mqtt

import camera.CameraProtocol.CameraEvent
import camera.modules.CameraModules
import config.MqttConfig
import eventbus.CameraEventBus
import mqtt.MqttProtocol._
import system.{O2MCommand, WrappedMqttConnectionCmd}
import utils.ActorContextImplicits

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy, ActorRef => TypedActorRef}
import org.apache.pekko.stream.connectors.mqtt.scaladsl.{MqttFlow, MqttMessageWithAck}
import org.apache.pekko.stream.connectors.mqtt.{MqttConnectionSettings, MqttMessage, MqttQoS, MqttSubscriptions}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ActorAttributes, CompletionStrategy, OverflowStrategy, Supervision}
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import javax.net.ssl.SSLContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

case class MqttBaseTopicProvider(base: String)

object MqttProtocol {

    sealed trait MqttCmd

    case class CameraEventReceived(ev: CameraEvent, ack: Option[() => Future[Done]]) extends MqttCmd

    case object MQTTConnected extends MqttCmd

    case object StreamCompleteInmediate extends MqttCmd

    case class TerminatedWithError(err: Throwable) extends MqttCmd

    case class CamFinished(id: String) extends MqttCmd

    case object TerminatedOk extends MqttCmd

    case object Terminate extends MqttCmd

}

object MqttSystem extends ActorContextImplicits {

    def apply(cfg: MqttConfig, parent: TypedActorRef[O2MCommand]): Behavior[MqttCmd] = {
        val baseTopic = cfg.base_name.getOrElse("cam2mqtt")
        Behaviors.supervise[MqttCmd] {
            Behaviors.setup { implicit context =>

                context.setLoggerName(MqttSystem.getClass)
                context.log.info(s"starting MQTT client...")

                val act = context.messageAdapter[CameraEvent](ev => CameraEventReceived(ev, None))

                val src = mqttStreamSource(baseTopic, connectionSettings(cfg))
                val ((mqttStream, subscribed), sinkResult) = src.run()
                // add mqtt stream event handlers
                sinkResult.recover {
                    case e: Throwable =>
                        context.self ! TerminatedWithError(e)
                        throw e
                }
                subscribed.map { _ =>
                    context.self ! MQTTConnected
                }

                Behaviors.receiveMessagePartial[MqttCmd] {
                    case MQTTConnected =>
                        context.log.info("mqtt client successfully connected")
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_MOTION)
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_OBJECT_DETECTION)
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_AVAILABILITY)
                        CameraEventBus.bus.subscribe(act.toClassic, CameraEventBus.TOPIC_OTHER)
                        parent ! WrappedMqttConnectionCmd(MQTTConnected)
                        connected(act.toClassic, mqttStream)
                    case TerminatedWithError(err) =>
                        context.log.error("mqtt client connection error", err)
                        // stream already failed. just force restart through supervisor
                        throw err
                    case Terminate =>
                        mqttStream ! Terminate
                        terminatingOk()
                }
            }
        }.onFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 4.seconds, maxBackoff = 1.minute, randomFactor = 0.2))
    }

    private def connected(eventBusListener: ActorRef, mqttStream: ActorRef): Behavior[MqttCmd] = {
        Behaviors.setup { implicit context =>
            Behaviors.receiveMessagePartial {
                case ev: CameraEventReceived =>
                    mqttStream ! ev
                    Behaviors.same
                case TerminatedWithError(err) =>
                    context.log.error("mqtt client connection error", err)
                    CameraEventBus.bus.unsubscribe(eventBusListener)
                    // stream already failed. just force restart through supervisor
                    throw err
                case Terminate =>
                    CameraEventBus.bus.unsubscribe(eventBusListener)
                    mqttStream ! Terminate
                    terminatingOk()
                case TerminatedOk =>
                    Behaviors.same
            }
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

    private def mqttStreamSource(baseTopic: String, settings: MqttConnectionSettings): RunnableGraph[((ActorRef, Future[Done]), Future[Done])] = {
        val inputTopic = s"$baseTopic/camera/+/command/#"

        val mqttFlow: Flow[MqttMessageWithAck, MqttMessageWithAck, Future[Done]] =
            MqttFlow.atLeastOnceWithAck(
                settings,
                MqttSubscriptions(inputTopic, MqttQoS.AtLeastOnce),
                bufferSize = 8,
                MqttQoS.AtLeastOnce
            )
        val alwaysStop: Supervision.Decider = _ => Supervision.Stop
        val stratAlwaysStop = ActorAttributes.supervisionStrategy(alwaysStop)

        implicit val baseTopicProv: MqttBaseTopicProvider = MqttBaseTopicProvider(baseTopic)

        val compStage: PartialFunction[Any, CompletionStrategy] = {
            case Terminate => CompletionStrategy.immediately
        }
        val failMatcher: PartialFunction[Any, Throwable] = {
            case Terminate => new Exception("termination request")
        }

        Source
                .actorRef[CameraEventReceived](compStage, failMatcher, 200, OverflowStrategy.dropTail)
                .map { camEv =>
                    event2Mqtt(camEv.ev) match {
                        case Some(e) if camEv.ack.isDefined =>
                            Some(e.withAck(camEv.ack.get))
                        case Some(e) =>
                            Some(e.withoutAck())
                        case _ => None
                    }
                }
                .filter(_.isDefined)
                .map(_.get)
                .viaMat(mqttFlow)(Keep.both)
                .map { ev =>
                    Try {
                        MqttCommands.inputCommand(baseTopic, ev.message)
                    }
                    ev
                }
                .mapAsyncUnordered(4) {
                    _.ack()
                }
                .toMat(Sink.ignore)(Keep.both)
                .withAttributes(stratAlwaysStop)
    }

    private def connectionSettings(cfg: MqttConfig): MqttConnectionSettings = {
        Option(MqttConnectionSettings(
            s"${if (cfg.ssl) "ssl" else "tcp"}://${cfg.host}:${cfg.port}",
            s"cam2mqtt-client-${System.currentTimeMillis()}",
            new MemoryPersistence()
        ).withAuth(cfg.username, cfg.password)
                .withAutomaticReconnect(false)
                .withCleanSession(false)).map { v =>
            if (cfg.ssl) v.withSocketFactory(SSLContext.getDefault.getSocketFactory) else v
        }.get
    }

    private def event2Mqtt(ev: CameraEvent)(implicit _btp: MqttBaseTopicProvider): Option[MqttMessage] = (ev match {
        // transform some events?
        case ev => moduleEvent2Mqtt(ev)
    }).map(ev => ev.withTopic(s"${_btp.base}/${ev.topic}")) // prepend base topic

    private def moduleEvent2Mqtt(ev: CameraEvent)(implicit _btp: MqttBaseTopicProvider): Option[MqttMessage] =
        CameraModules.MQTT_MODULES.find(_.moduleId == ev.moduleId).flatMap(_.eventToMqttMessage(ev))

    private implicit class MqttMessageAckExt(msg: MqttMessage) {
        def withAck(f: () => Future[Done]): MqttMessageWithAck = {
            new MqttMessageWithAck {
                override val message: MqttMessage = msg

                override def ack(): Future[Done] = f()
            }
        }

        def withoutAck(): MqttMessageWithAck = {
            new MqttMessageWithAck {
                override val message: MqttMessage = msg

                override def ack(): Future[Done] = Future.successful(Done)
            }
        }
    }

}
