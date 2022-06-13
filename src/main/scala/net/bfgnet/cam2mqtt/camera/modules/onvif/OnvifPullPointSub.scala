package net.bfgnet.cam2mqtt.camera.modules.onvif

import java.io.IOException
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import net.bfgnet.cam2mqtt.camera.CameraConfig.{CameraInfo, OnvifCameraModuleConfig}
import net.bfgnet.cam2mqtt.camera.CameraProtocol._
import net.bfgnet.cam2mqtt.camera.modules.onvif.OnvifSubProtocol._
import net.bfgnet.cam2mqtt.onvif.OnvifRequests
import net.bfgnet.cam2mqtt.onvif.OnvifSubscriptionRequests.SubscriptionInfo
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object OnvifPullPointSub extends ActorContextImplicits {

    def apply(parent: ActorRef[CameraCmd], info: CameraInfo, config: OnvifCameraModuleConfig): Behavior[OnvifSubCmd] = stopped(parent, info, config)

    private def stopped(parent: ActorRef[CameraCmd], info: CameraInfo, config: OnvifCameraModuleConfig): Behavior[OnvifSubCmd] = {
        Behaviors.setup { implicit context =>
            context.setLoggerName(OnvifPullPointSub.getClass)

            // create subscription on start
            context.log.debug(s"starting pullpoint subscription on device ${info.copyWithPrivacy()}")
            val subs = OnvifRequests.createPullPointSubscription(info.host, config.port, info.username, info.password, 60)
            context.pipeToSelf(subs) {
                case Success(value) => Subscribed(value)
                case Failure(err) => SubscriptionError(err)
            }

            Behaviors.receiveMessagePartial[OnvifSubCmd] {
                case Subscribed(sub) =>
                    notifyAvailability(parent, info.cameraId, available = true)
                    context.self ! PullMessages
                    subscribed(parent, info, config, sub)
                case SubscriptionError(err) =>
                    context.log.error("subscription error", err)
                    notifyAvailability(parent, info.cameraId, available = false)
                    Behaviors.stopped
            }
        }
    }

    private def subscribed(parent: ActorRef[CameraCmd], info: CameraInfo, config: OnvifCameraModuleConfig, subscription: SubscriptionInfo): Behavior[OnvifSubCmd] = {
        Behaviors.setup { implicit context =>
            val timeToRenew = subscription.terminationTime - System.currentTimeMillis()
            context.log.debug(s"pullpoint subscription renewed: millis remaining until next renew = $timeToRenew")
            val renewEv = context.scheduleOnce(timeToRenew.millis, context.self, RenewSubscription)
            Behaviors.receiveMessagePartial[OnvifSubCmd] {
                case RenewSubscription =>
                    renewEv.cancel()
                    context.log.debug(s"Renew subscription")
                    val subs = OnvifRequests.renewSubscription(info.host, config.port, info.username, info.password, subscription.address, 60, subscription.isPullPointSub)
                    context.pipeToSelf(subs) {
                        case Success(value) => Subscribed(value)
                        case Failure(err) => SubscriptionError(err)
                    }
                    Behaviors.same
                case Subscribed(sub) =>
                    subscribed(parent, info, config, sub)
                case SubscriptionError(err: IOException) =>
                    context.log.error("subscription IO error", err)
                    notifyAvailability(parent, info.cameraId, available = false)
                    context.self ! TerminateSubscription
                    Behaviors.same
                case SubscriptionError(err) =>
                    context.log.error("subscription error", err)
                    notifyAvailability(parent, info.cameraId, available = false)
                    throw err
                case Unsubscribed =>
                    Behaviors.stopped
                case PullPointEvents(camId, events) =>
                    // Send camera event to parent
                    events.map(e => CameraModuleEvent(camId, OnvifModule.moduleId, e)).foreach(parent ! _)
                    // keep pulling messages
                    context.self ! PullMessages
                    Behaviors.same
                case PullMessages =>
                    // pull messages from subscription
                    val pullMsgs = OnvifRequests.pullMessagesFromSubscription(info.host, config.port, info.username, info.password, subscription.address, 10)
                    context.pipeToSelf(pullMsgs) {
                        case Success(value) =>
                            val messages = parsePullPointMessage(info.cameraId, value).map(List(_)).getOrElse(Nil)
                            context.log.trace(s"pulled ${messages.size} messages")
                            PullPointEvents(info.cameraId, messages)
                        case Failure(err) => SubscriptionError(err)
                    }
                    Behaviors.same
                case TerminateSubscription =>
                    // cleanup resources on device
                    val f = OnvifRequests.unsubscribe(info.host, config.port, info.username, info.password, subscription.address)
                    context.pipeToSelf(f)(_ => Unsubscribed)
                    finishing()
            }.receiveSignal {
                case (_, PostStop) =>
                    OnvifRequests.unsubscribe(info.host, config.port, info.username, info.password, subscription.address)
                    renewEv.cancel()
                    finishing()
            }
        }
    }

    private def finishing(): Behavior[OnvifSubCmd] = {
        Behaviors.receiveMessagePartial[OnvifSubCmd] {
            case Unsubscribed =>
                Behaviors.stopped
            case _ =>
                Behaviors.same
        }
    }

    private def parsePullPointMessage(id: String, xml: String): Option[CameraEvent] = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val notif = doc.select("*|Envelope > *|Body > *|PullMessagesResponse > *|NotificationMessage")
        val topic = notif.select("*|Topic").text()
        topic match {
            case "tns1:RuleEngine/CellMotionDetector/Motion" =>
                val motion = notif.select("*|Message > *|Data > *|SimpleItem[Name='IsMotion']")
                if (!motion.isEmpty) {
                    val isMotion = motion.attr("Value") == "true"
                    Option(CameraMotionEvent(id, OnvifModule.moduleId, isMotion))
                } else None
            case _ => None
        }
    }

    private def notifyAvailability(parent: ActorRef[CameraCmd], cameraId: String, available: Boolean) = {
        parent ! CameraModuleEvent(cameraId, OnvifModule.moduleId, CameraAvailableEvent(cameraId, available))
    }

}
