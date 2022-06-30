package net.bfgnet.cam2mqtt.camera.modules.onvif

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import net.bfgnet.cam2mqtt.camera.CameraConfig.{CameraInfo, OnvifCameraModuleConfig}
import net.bfgnet.cam2mqtt.camera.CameraProtocol.{CameraAvailableEvent, CameraCmd, CameraEvent, CameraModuleEvent, CameraMotionEvent, CameraObjectDetectionEvent}
import net.bfgnet.cam2mqtt.camera.modules.onvif.OnvifSubProtocol._
import net.bfgnet.cam2mqtt.config.WebhookConfig
import net.bfgnet.cam2mqtt.onvif.OnvifRequests
import net.bfgnet.cam2mqtt.onvif.OnvifSubscriptionRequests.SubscriptionInfo
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.jdk.CollectionConverters._

object OnvifWebhookSub extends ActorContextImplicits {

    def apply(parent: ActorRef[CameraCmd], webhookConfig: WebhookConfig, info: CameraInfo,
              config: OnvifCameraModuleConfig): Behavior[OnvifSubCmd] = stopped(parent, webhookConfig, info, config)

    private def stopped(parent: ActorRef[CameraCmd], webhookConfig: WebhookConfig, info: CameraInfo, config: OnvifCameraModuleConfig): Behavior[OnvifSubCmd] = {
        Behaviors.setup { implicit context =>
            // create subscription on start
            context.log.debug(s"starting webhook subscription on device ${info.copyWithPrivacy()}")
            val webhookUrl = concatUrl(webhookConfig.external_url, s"onvif/webhook/camera/${info.cameraId}")
            val subs = OnvifRequests.subscribe(info.host, config.port, info.username, info.password, webhookUrl, 60)
            context.pipeToSelf(subs) {
                case Success(value) => Subscribed(value)
                case Failure(err) => SubscriptionError(err)
            }

            Behaviors.receiveMessagePartial[OnvifSubCmd] {
                case Subscribed(sub) =>
                    notifyAvailability(parent, info.cameraId, available = true)
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
            context.log.debug(s"webhook subscription renewed: millis remaining until next renew = ${subscription.terminationTime - System.currentTimeMillis()}")
            val timeToRenew = subscription.terminationTime - System.currentTimeMillis() - 15
            context.scheduleOnce(timeToRenew.millis, context.self, RenewSubscription)
            Behaviors.receiveMessagePartial[OnvifSubCmd] {
                case RenewSubscription =>
                    context.log.debug(s"Renew subscription")
                    val subs = OnvifRequests.renewSubscription(info.host, config.port, info.username, info.password, subscription.address, 60, subscription.isPullPointSub)
                    context.pipeToSelf(subs) {
                        case Success(value) => Subscribed(value)
                        case Failure(err) => SubscriptionError(err)
                    }
                    Behaviors.same
                case SubscriptionError(err) =>
                    context.log.error("subscription error", err)
                    notifyAvailability(parent, info.cameraId, available = false)
                    context.self ! TerminateSubscription
                    Behaviors.same
                case Subscribed(sub) =>
                    subscribed(parent, info, config, sub)
                case WebhookNotification(msg) =>
                    val ev = parseNotification(info.cameraId, msg)
                    // send camera event to parent
                    ev.map(e => CameraModuleEvent(info.cameraId, OnvifModule.moduleId, e)).foreach(parent ! _)
                    Behaviors.same
                case TerminateSubscription =>
                    // cleanup resources on device
                    val f = OnvifRequests.unsubscribe(info.host, config.port, info.username, info.password, subscription.address)
                    context.pipeToSelf(f)(_ => Unsubscribed)
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

    private def parseNotification(id: String, xml: String)(implicit _context: ActorContext[_]): List[CameraEvent] = {
        _context.log.trace(s"subscription message: $xml")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val notifs = doc.select("*|Envelope > *|Body > *|Notify > *|NotificationMessage").listIterator().asScala.toList
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

    private def concatUrl(base: String, path: String): String = {
        val _base = if (base.endsWith("/")) {
            base.substring(0, base.length - 1)
        } else base
        val _path = if (path.startsWith("/")) {
            path.substring(1)
        } else path
        s"${_base}/${_path}"
    }
}
