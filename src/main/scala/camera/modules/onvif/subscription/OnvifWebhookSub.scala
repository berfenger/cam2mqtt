package net.bfgnet.cam2mqtt
package camera.modules.onvif.subscription

import camera.CameraConfig.{CameraInfo, OnvifCameraModuleConfig}
import camera.CameraProtocol._
import camera.modules.onvif.OnvifModule
import OnvifSubProtocol._
import config.WebhookConfig
import onvif.OnvifRequests
import onvif.OnvifSubscriptionRequests.SubscriptionInfo
import utils.ActorContextImplicits

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object OnvifWebhookSub extends ActorContextImplicits with OnvifMessageParser {

    private val MESSAGE_PARSER_ROUTE = "*|Envelope > *|Body > *|Notify > *|NotificationMessage"

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
                    val ev = parseMessage(MESSAGE_PARSER_ROUTE)(info.cameraId, msg)
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
