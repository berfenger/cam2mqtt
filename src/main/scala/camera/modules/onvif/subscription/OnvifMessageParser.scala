package net.bfgnet.cam2mqtt
package camera.modules.onvif.subscription

import camera.CameraProtocol.{CameraEvent, CameraMotionEvent, CameraObjectDetectionEvent, CameraVisitorEvent}
import camera.modules.onvif.OnvifModule

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

import scala.jdk.CollectionConverters._

trait OnvifMessageParser {

    protected def parseMessage(messageRoute: String)(cameraId: String, xml: String)(implicit _context: ActorContext[_]): List[CameraEvent] = {
        _context.log.trace(s"subscription message: $xml")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val notifs = doc.select(messageRoute).listIterator().asScala.toList
        notifs.flatMap { n =>
            val topic = n.select("*|Topic").text()
            stripNS(topic) match {
                case "RuleEngine/CellMotionDetector/Motion" =>
                    parseMotionEvent(cameraId, n)
                // AI object detection on Reolink cameras (firmware >= 3.1.0.951, April 2022)
                case "RuleEngine/MyRuleDetector/PeopleDetect" =>
                    parseAIDetectionEvent(cameraId, n, "people")
                case "RuleEngine/MyRuleDetector/FaceDetect" =>
                    parseAIDetectionEvent(cameraId, n, "face")
                case "RuleEngine/MyRuleDetector/VehicleDetect" =>
                    parseAIDetectionEvent(cameraId, n, "vehicle")
                case "RuleEngine/MyRuleDetector/DogCatDetect" =>
                    parseAIDetectionEvent(cameraId, n, "pet")
                case "RuleEngine/MyRuleDetector/Visitor" =>
                    parseVisitorEvent(cameraId, n)
                case _ => None
            }
        }
    }

    private def parseMotionEvent(id: String, notif: Element) = {
        val motion = notif.select("*|Message[PropertyOperation='Changed'] > *|Data > *|SimpleItem[Name='IsMotion']")
        if (!motion.isEmpty) {
            val isMotion = motion.attr("Value") == "true"
            Option(CameraMotionEvent(id, OnvifModule.moduleId, isMotion))
        } else None
    }

    private def parseAIDetectionEvent(id: String, notif: Element, objectClass: String) = {
        val motion = notif.select("*|Message[PropertyOperation='Changed'] > *|Data > *|SimpleItem[Name='State']")
        if (!motion.isEmpty) {
            val isDect = motion.attr("Value") == "true"
            Option(CameraObjectDetectionEvent(id, OnvifModule.moduleId, objectClass, isDect))
        } else None
    }

    private def parseVisitorEvent(id: String, notif: Element) = {
        val visitor = notif.select("*|Message[PropertyOperation='Changed'] > *|Data > *|SimpleItem[Name='State']")
        if (!visitor.isEmpty) {
            val isDect = visitor.attr("Value") == "true"
            Option(CameraVisitorEvent(id, OnvifModule.moduleId, isDect))
        } else None
    }

    private def stripNS(str: String) = {
        str.indexOf(":") match {
            case idx if idx > 0 => str.substring(idx + 1)
            case _ => str
        }
    }
}
