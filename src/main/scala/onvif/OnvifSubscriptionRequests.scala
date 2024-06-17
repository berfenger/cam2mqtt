package net.bfgnet.cam2mqtt
package onvif

import java.text.SimpleDateFormat
import java.time.ZoneId

import org.apache.pekko.actor.ClassicActorSystemProvider
import onvif.OnvifSubscriptionRequests.SubscriptionInfo
import utils.DateTimeUtils
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

import scala.concurrent.{ExecutionContext, Future}

trait OnvifSubscriptionRequests extends OnvifRequest with OnvifAuth {

    def subscribe(host: String, port: Int,
                  username: String, password: String, callbackAddress: String, timeSeconds: Long)
                 (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[SubscriptionInfo] = {

        val sign = auth(username, password)
        val xml = OnvifSubscriptionTemplates.SUBSCRIBE_TMPL
                .replace("{Security}", sign.appliedToXML())
                .replace("{Address}", callbackAddress)
                .replace("{InitialTerminationTime}", s"PT${timeSeconds}S")

        val prevTime = System.currentTimeMillis()

        req(host, port, xml, List("action" -> OnvifSubscriptionTemplates.SUBSCRIBE_ACTION))
                .map(parseSubscriptionResponse)
                .map(OnvifSubscriptionRequests.applySubscriptionTerminationTimeWorkaround(prevTime, timeSeconds * 1000))
    }

    def renewSubscription(host: String, port: Int,
                          username: String, password: String, subscriptionAddress: String, timeSeconds: Long, isPullPointSub: Boolean)
                         (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[SubscriptionInfo] = {

        val sign = auth(username, password)
        val xml = OnvifSubscriptionTemplates.RENEW_SUBS_TMPL
                .replace("{Security}", sign.appliedToXML())
                .replace("{To}", subscriptionAddress)
                .replace("{TerminationTime}", s"PT${timeSeconds}S")

        val prevTime = System.currentTimeMillis()

        req(host, port, xml, List("action" -> OnvifSubscriptionTemplates.RENEW_SUBS_ACTION))
                .map(parseRenewSubscriptionResponse)
                .map(SubscriptionInfo(subscriptionAddress, _, isPullPointSub = isPullPointSub))
                .map(OnvifSubscriptionRequests.applySubscriptionTerminationTimeWorkaround(prevTime, timeSeconds * 1000))
    }

    def unsubscribe(host: String, port: Int,
                    username: String, password: String, subscriptionAddress: String)
                   (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[Boolean] = {

        val sign = auth(username, password)
        val xml = OnvifSubscriptionTemplates.UNSUBSCRIBE_TMPL
                .replace("{Security}", sign.appliedToXML())
                .replace("{To}", subscriptionAddress)

        req(host, port, xml, List("action" -> OnvifSubscriptionTemplates.UNSUBSCRIBE_ACTION))
                .map(parseUnsubscribeResponse)
                .map { r =>
                    _as.classicSystem.log.debug(s"Subscription deleted on device: $subscriptionAddress")
                    r
                }
    }

    private def parseSubscriptionResponse(xml: String): SubscriptionInfo = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val resp = doc.select("*|Envelope > *|Body > *|SubscribeResponse")
        val addr = resp.select("*|SubscriptionReference > *|Address")
        val terminationTime = resp.select("*|TerminationTime")
        if (!addr.isEmpty && addr.text().length > 0 && !terminationTime.isEmpty && terminationTime.text().length > 0) {
            SubscriptionInfo(addr.text(), OnvifSubscriptionTemplates.TIME_FMT.parse(terminationTime.text()).getTime, isPullPointSub = false)
        } else if (!doc.select("*|Envelope > *|Body > *|Fault").isEmpty) {
            val reason = Option(doc.select("*|Envelope > *|Body > *|Fault> *|Reason").text()).filter(_.length > 0).getOrElse("unknown")
            throw new Exception(s"could not create subscription. reason: $reason")
        } else {
            throw new Exception("could not parse SubscribeResponse")
        }
    }

    private def parseRenewSubscriptionResponse(xml: String): Long = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val terminationTime = doc.select("*|Envelope > *|Body > *|RenewResponse > *|TerminationTime")
        if (!terminationTime.isEmpty && terminationTime.text().length > 0) {
            OnvifSubscriptionTemplates.TIME_FMT.parse(terminationTime.text()).getTime
        } else if (!doc.select("*|Envelope > *|Body > *|Fault").isEmpty) {
            val reason = Option(doc.select("*|Envelope > *|Body > *|Fault> *|Reason").text()).filter(_.length > 0).getOrElse("unknown")
            throw new Exception(s"could not create subscription. reason: $reason")
        } else {
            throw new Exception("could not parse renew SubscribeResponse")
        }
    }

    private def parseUnsubscribeResponse(xml: String): Boolean = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val terminationTime = doc.select("*|Envelope > *|Body > *|UnsubscribeResponse")
        !terminationTime.isEmpty
    }
}

object OnvifSubscriptionRequests {

    case class SubscriptionInfo(address: String, terminationTime: Long, isPullPointSub: Boolean)

    private val GRACE_TIME_MILLIS = 10000

    def applySubscriptionTerminationTimeWorkaround(prevTime: Long, subsMillis: Long)(sub: SubscriptionInfo): SubscriptionInfo = {
        // workaround: some cameras (reolink) have a bug that causes terminationTime to not be updated
        // we need to reduce time by 10 seconds as some cameras don't respect the 10 seconds specified on onvif
        if (sub.terminationTime < System.currentTimeMillis() || sub.terminationTime > prevTime + subsMillis - GRACE_TIME_MILLIS) {
            sub.copy(terminationTime = prevTime + subsMillis - GRACE_TIME_MILLIS)
        } else sub
    }
}

private object OnvifSubscriptionTemplates {
    def TIME_FMT: SimpleDateFormat = DateTimeUtils.dateFormatter("yyyy-MM-dd'T'HH:mm:ss'Z'", ZoneId.of("UTC"))

    val SUBSCRIBE_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    </soap:Header>
          |    <soap:Body>
          |        <b:Subscribe>
          |            <b:ConsumerReference>
          |                <add:Address>{Address}</add:Address>
          |            </b:ConsumerReference>
          |            <b:InitialTerminationTime>{InitialTerminationTime}</b:InitialTerminationTime>
          |        </b:Subscribe>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val RENEW_SUBS_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    <add:Action>http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest</add:Action>
          |    <add:To>{To}</add:To>
          |    </soap:Header>
          |    <soap:Body>
          |        <b:Renew>
          |            <b:TerminationTime>{TerminationTime}</b:TerminationTime>
          |        </b:Renew>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val UNSUBSCRIBE_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    <add:Action>http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest</add:Action>
          |    <add:To>{To}</add:To>
          |    </soap:Header>
          |    <soap:Body>
          |        <b:Unsubscribe/>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val SUBSCRIBE_ACTION = "http://docs.oasis-open.org/wsn/bw-2/NotificationProducer/SubscribeRequest"
    val RENEW_SUBS_ACTION = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest"
    val UNSUBSCRIBE_ACTION = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest"
}
