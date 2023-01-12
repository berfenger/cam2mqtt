package net.bfgnet.cam2mqtt
package camera.modules.onvif

import java.io.IOException
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import camera.CameraConfig.{CameraInfo, OnvifCameraModuleConfig}
import camera.CameraProtocol._
import camera.modules.onvif.OnvifSubProtocol._
import onvif.OnvifRequests
import onvif.OnvifSubscriptionRequests.SubscriptionInfo
import utils.ActorContextImplicits
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.jdk.CollectionConverters._

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
                            val messages = parsePullPointMessage(info.cameraId, value)
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

    private def parsePullPointMessage(id: String, xml: String)(implicit _context: ActorContext[_]): List[CameraEvent] = {
        _context.log.trace(s"subscription message: $xml")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val notifs = doc.select("*|Envelope > *|Body > *|PullMessagesResponse > *|NotificationMessage").listIterator().asScala.toList
        notifs.flatMap { n =>
            val topic = n.select("*|Topic").text()
            stripNS(topic) match {
                case "RuleEngine/CellMotionDetector/Motion" =>
                    val motion = n.select("*|Message[PropertyOperation='Changed'] > *|Data > *|SimpleItem[Name='IsMotion']")
                    if (!motion.isEmpty) {
                        val isMotion = motion.attr("Value") == "true"
                        Option(CameraMotionEvent(id, OnvifModule.moduleId, isMotion))
                    } else None
                // AI object detection on Reolink cameras (firmware >= 3.1.0.951, April 2022)
                case "RuleEngine/MyRuleDetector/PeopleDetect" =>
                    parseAIDetectionEvent(id, n, "people")
                case "RuleEngine/MyRuleDetector/FaceDetect" =>
                    parseAIDetectionEvent(id, n, "face")
                case "RuleEngine/MyRuleDetector/VehicleDetect" =>
                    parseAIDetectionEvent(id, n, "vehicle")
                case "RuleEngine/MyRuleDetector/DogCatDetect" =>
                    parseAIDetectionEvent(id, n, "pet")
                case _ => None
            }
        }
    }

    private def parseAIDetectionEvent(id: String, notif: Element, objectClass: String) = {
        val motion = notif.select("*|Message[PropertyOperation='Changed'] > *|Data > *|SimpleItem[Name='State']")
        if (!motion.isEmpty) {
            val isDect = motion.attr("Value") == "true"
            Option(CameraObjectDetectionEvent(id, OnvifModule.moduleId, objectClass, isDect))
        } else None
    }

    private def stripNS(str: String) = {
        str.indexOf(":") match {
            case idx if idx > 0 => str.substring(idx + 1)
            case _ => str
        }
    }

    private def notifyAvailability(parent: ActorRef[CameraCmd], cameraId: String, available: Boolean) = {
        parent ! CameraModuleEvent(cameraId, OnvifModule.moduleId, CameraAvailableEvent(cameraId, available))
    }

}