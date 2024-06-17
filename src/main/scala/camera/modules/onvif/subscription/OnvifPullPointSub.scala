package net.bfgnet.cam2mqtt
package camera.modules.onvif.subscription

import camera.CameraConfig.{CameraInfo, OnvifCameraModuleConfig}
import camera.CameraProtocol._
import camera.modules.onvif.OnvifModule
import OnvifSubProtocol._
import onvif.OnvifRequests
import onvif.OnvifSubscriptionRequests.SubscriptionInfo
import utils.ActorContextImplicits

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}

import java.io.IOException
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object OnvifPullPointSub extends ActorContextImplicits with OnvifMessageParser {

    private val MESSAGE_PARSER_ROUTE = "*|Envelope > *|Body > *|PullMessagesResponse > *|NotificationMessage"

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
                            val messages = parseMessage(MESSAGE_PARSER_ROUTE)(info.cameraId, value)
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

    private def notifyAvailability(parent: ActorRef[CameraCmd], cameraId: String, available: Boolean) = {
        parent ! CameraModuleEvent(cameraId, OnvifModule.moduleId, CameraAvailableEvent(cameraId, available))
    }

}
