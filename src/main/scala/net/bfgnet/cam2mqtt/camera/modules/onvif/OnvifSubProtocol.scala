package net.bfgnet.cam2mqtt.camera.modules.onvif

import net.bfgnet.cam2mqtt.camera.CameraProtocol.CameraEvent
import net.bfgnet.cam2mqtt.onvif.OnvifSubscriptionRequests.SubscriptionInfo

object OnvifSubProtocol {

    sealed trait OnvifSubCmd

    case object RenewSubscription extends OnvifSubCmd

    case object PullMessages extends OnvifSubCmd

    case object Unsubscribed extends OnvifSubCmd

    case class Subscribed(info: SubscriptionInfo) extends OnvifSubCmd

    case class SubscriptionError(error: Throwable) extends OnvifSubCmd

    case class WebhookNotification(message: String) extends OnvifSubCmd

    case class PullPointEvents(id: String, events: List[CameraEvent]) extends OnvifSubCmd

    case object TerminateSubscription extends OnvifSubCmd

}
