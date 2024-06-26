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

trait OnvifPullPointSubscriptionRequests extends OnvifRequest with OnvifAuth {

    def createPullPointSubscription(host: String, port: Int,
                                    username: String, password: String, timeSeconds: Long)
                                   (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[SubscriptionInfo] = {

        val sign = auth(username, password)
        val xml = OnvifPullPointSubscriptionTemplates.CREATE_PPS_TMPL
                .replace("{Security}", sign.appliedToXML())
                .replace("{InitialTerminationTime}", s"PT${timeSeconds}S")

        val prevTime = System.currentTimeMillis()

        req(host, port, xml, List("action" -> OnvifPullPointSubscriptionTemplates.CREATE_PPA_ACTION))
                .map(parseCreatePPSResponse)
                .map(OnvifSubscriptionRequests.applySubscriptionTerminationTimeWorkaround(prevTime, timeSeconds * 1000))
    }

    def pullMessagesFromSubscription(host: String, port: Int,
                                     username: String, password: String, subscriptionId: String, timeoutSeconds: Long)
                                    (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String] = {

        val sign = auth(username, password)
        val xml = OnvifPullPointSubscriptionTemplates.PULL_MSGS_TMPL
                .replace("{Security}", sign.appliedToXML())
                .replace("{To}", subscriptionId)
                .replace("{Timeout}", s"PT${timeoutSeconds}S")
                .replace("{MessageLimit}", 10.toString)

        req(host, port, xml, List("action" -> OnvifPullPointSubscriptionTemplates.PULL_MSGS_ACTION))
    }

    private def parseCreatePPSResponse(xml: String): SubscriptionInfo = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val resp = doc.select("*|Envelope > *|Body > *|CreatePullPointSubscriptionResponse")
        val addr = resp.select("*|SubscriptionReference > *|Address")
        val terminationTime = resp.select("*|TerminationTime")
        if (!addr.isEmpty && addr.text().length > 0 && !terminationTime.isEmpty && terminationTime.text().length > 0) {
            SubscriptionInfo(addr.text(), OnvifSubscriptionTemplates.TIME_FMT.parse(terminationTime.text()).getTime, isPullPointSub = true)
        } else if (!doc.select("*|Envelope > *|Body > *|Fault").isEmpty) {
            val reason = Option(doc.select("*|Envelope > *|Body > *|Fault> *|Reason").text()).filter(_.length > 0).getOrElse("unknown")
            throw new Exception(s"could not create subscription. reason: $reason")
        } else {
            throw new Exception("could not parse SubscribeResponse")
        }
    }

}

private object OnvifPullPointSubscriptionTemplates {

    def TIME_FMT: SimpleDateFormat = DateTimeUtils.dateFormatter("yyyy-MM-dd'T'HH:mm:ss'Z'", ZoneId.of("UTC"))

    val CREATE_PPS_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    </soap:Header>
          |    <soap:Body>
          |        <ns1:CreatePullPointSubscription xmlns:ns1="http://www.onvif.org/ver10/events/wsdl">
          |	           <ns1:InitialTerminationTime>{InitialTerminationTime}</InitialTerminationTime>
          |	       </ns1:CreatePullPointSubscription>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val PULL_MSGS_TMPL =
        """<soap:Envelope xmlns:tev="http://www.onvif.org/ver10/events/wsdl" xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    <add:Action>http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest</add:Action>
          |    <add:To mustUnderstand="1">{To}</add:To>
          |    </soap:Header>
          |    <soap:Body>
          |        <tev:PullMessages>
          |          <tev:Timeout>{Timeout}</tev:Timeout>
          |          <tev:MessageLimit>{MessageLimit}</tev:MessageLimit>
          |        </tev:PullMessages>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val CREATE_PPA_ACTION = "http://www.onvif.org/ver10/events/wsdl/EventPortType/CreatePullPointSubscriptionRequest"

    val PULL_MSGS_ACTION = "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest"
}
