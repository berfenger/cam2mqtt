package net.bfgnet.cam2mqtt.onvif

import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.{Calendar, GregorianCalendar, TimeZone}

import akka.actor.ClassicActorSystemProvider
import net.bfgnet.cam2mqtt.onvif.OnvifGetPropertiesRequests.{OnvifCapabilitiesResponse, OnvifDateTimeResponse, OnvifEventPropertiesResponse}
import net.bfgnet.cam2mqtt.onvif.OnvifGetPropertiesTemplates.DATETIME_PARSER
import net.bfgnet.cam2mqtt.utils.DateTimeUtils
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.select.Elements

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait OnvifGetPropertiesRequests extends OnvifRequest with OnvifAuth {

    def getEventProperties(host: String, port: Int, username: String, password: String)
                          (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[OnvifEventPropertiesResponse] = {

        val sign = auth(username, password)
        val xml = OnvifGetPropertiesTemplates.GET_EVPROPS_TMPL
                .replace("{Security}", sign.appliedToXML())

        req(host, port, xml)
                .map { r =>
                    val doc = Jsoup.parse(r, "", Parser.xmlParser())
                    val topics = doc.select("*|Envelope > *|Body > *|Notify > *|GetEventPropertiesResponse > *|TopicSet")
                    OnvifEventPropertiesResponse(!topics.isEmpty)
                }
    }

    def getCapabilities(host: String, port: Int, username: String, password: String)
                       (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[OnvifCapabilitiesResponse] = {

        val sign = auth(username, password)
        val xml = OnvifGetPropertiesTemplates.GET_CAPS_TMPL
                .replace("{Security}", sign.appliedToXML())

        req(host, port, xml)
                .map(parseCapabilities)
    }

    def getSystemDateTime(host: String, port: Int, username: String, password: String)
                         (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[OnvifDateTimeResponse] = {

        val sign = auth(username, password)
        val xml = OnvifGetPropertiesTemplates.GET_DATETIME_TMPL
                .replace("{Security}", sign.appliedToXML())

        req(host, port, xml)
                .map(parseSystemDateTime)
    }

    def setManualSystemDateTime(host: String, port: Int, username: String, password: String, utcMillis: Long)
                               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[Boolean] = {

        val date = new GregorianCalendar()
        date.setTimeZone(TimeZone.getTimeZone(ZoneId.of("UTC")))
        date.setTimeInMillis(utcMillis)
        val year = date.get(Calendar.YEAR)
        val month = date.get(Calendar.MONTH)
        val day = date.get(Calendar.DAY_OF_MONTH)
        val hour = date.get(Calendar.HOUR_OF_DAY)
        val min = date.get(Calendar.MINUTE)
        val sec = date.get(Calendar.SECOND)

        val sign = auth(username, password)
        val xml = OnvifGetPropertiesTemplates.SET_DATETIME_TMPL
                .replace("{Security}", sign.appliedToXML())
                .replace("{Type}", "Manual")
                .replace("{Year}", year.toString)
                .replace("{Month}", month.toString)
                .replace("{Day}", day.toString)
                .replace("{Hour}", hour.toString)
                .replace("{Minute}", min.toString)
                .replace("{Second}", sec.toString)
        req(host, port, xml)
                .map(parseSetSystemDateTime)
    }

    private def parseCapabilities(xml: String): OnvifCapabilitiesResponse = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val caps = doc.select("*|Envelope > *|Body > *|GetCapabilitiesResponse > *|Capabilities")
        val hasEvents = Option(caps.select("*|Events > *|XAddr")).filterNot(_.isEmpty).map(_.text()).exists(_.nonEmpty)
        val hasPPS = Option(caps.select("*|Events > *|WSPullPointSupport")).filterNot(_.isEmpty).map(_.text()).contains("true")
        val hasImaging = Option(caps.select("*|Imaging > *|XAddr")).filterNot(_.isEmpty).map(_.text()).exists(_.nonEmpty)
        val hasPTZ = Option(caps.select("*|PTZ > *|XAddr")).filterNot(_.isEmpty).map(_.text()).exists(_.nonEmpty)
        OnvifCapabilitiesResponse(hasEvents, hasPPS, hasImaging, hasPTZ)
    }

    private def parseSystemDateTime(xml: String): OnvifDateTimeResponse = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val utc = doc.select("*|Envelope > *|Body > *|GetSystemDateAndTimeResponse > *|SystemDateAndTime > *|UTCDateTime")
        val time = utc.select("*|Time")
        val date = utc.select("*|Date")
        val year = toInt(date.select("*|Year"))
        val month = toInt(date.select("*|Month"))
        val day = toInt(date.select("*|Day"))
        val hour = toInt(time.select("*|Hour"))
        val min = toInt(time.select("*|Minute"))
        val sec = toInt(time.select("*|Second"))
        ((year, month, day, hour, min, sec) match {
            case (Some(_year), Some(_month), Some(_day), Some(_hour), Some(_min), Some(_sec)) =>
                Option(DATETIME_PARSER.parse("%02d-%02d-%02dT%02d:%02d:%02d".format(_year, _month, _day, _hour, _min, _sec)).getTime)
            case _ => None
        }).map(t => OnvifDateTimeResponse(t)).getOrElse(throw new Exception("could not parse GetSystemDateAndTimeResponse"))
    }

    private def parseSetSystemDateTime(xml: String): Boolean = {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        !doc.select("*|Envelope > *|Body > *|SetSystemDateAndTimeResponse").isEmpty
    }

    private def toInt(el: Elements) =
        Option(el).map(_.text()).filterNot(_.length <= 0).flatMap(v => Try(v.toInt).toOption)
}

object OnvifGetPropertiesRequests {

    case class OnvifEventPropertiesResponse(hasTopics: Boolean)

    case class OnvifCapabilitiesResponse(hasEvents: Boolean, hasPullPointSupport: Boolean, hasImaging: Boolean, hasPTZ: Boolean)

    case class OnvifDateTimeResponse(utcMillis: Long)

}

private object OnvifGetPropertiesTemplates {
    def DATETIME_PARSER: SimpleDateFormat = DateTimeUtils.dateFormatter("yyyy-MM-dd'T'HH:mm:ss", ZoneId.of("UTC"))

    val GET_EVPROPS_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    </soap:Header>
          |    <soap:Body>
          |        <GetEventProperties xmlns="http://www.onvif.org/ver10/events/wsdl"/>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val GET_CAPS_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    </soap:Header>
          |    <soap:Body>
          |        <GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl">
          |			  <Category>All</Category>
          |		   </GetCapabilities>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val GET_DATETIME_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    </soap:Header>
          |    <soap:Body>
          |        <GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin

    val SET_DATETIME_TMPL =
        """<soap:Envelope xmlns:add="http://www.w3.org/2005/08/addressing" xmlns:b="http://docs.oasis-open.org/wsn/b-2" xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
          |    <soap:Header>
          |    {Security}
          |    </soap:Header>
          |    <soap:Body>
          |        <SetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl">
          |			<DateTimeType>
          |			{Type}
          |			</DateTimeType>
          |			<UTCDateTime>
          |				<Time xmlns="http://www.onvif.org/ver10/schema">
          |				  <Hour>{Hour}</Hour>
          |				  <Minute>{Minute}</Minute>
          |				  <Second>{Second}</Second>
          |				</Time>
          |				<Date xmlns="http://www.onvif.org/ver10/schema">
          |				  <Year>{Year}</Year>
          |				  <Month>{Month}</Month>
          |				  <Day>{Day}</Day>
          |				</Date>
          |			</UTCDateTime>
          |			</SetSystemDateAndTime>
          |    </soap:Body>
          |    </soap:Envelope>""".stripMargin
}
