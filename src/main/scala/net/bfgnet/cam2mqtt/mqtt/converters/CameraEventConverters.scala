package net.bfgnet.cam2mqtt.mqtt.converters

import akka.stream.alpakka.mqtt.MqttMessage
import net.bfgnet.cam2mqtt.camera.CameraProtocol.{CameraAvailableEvent, CameraEvent, CameraMotionEvent}
import net.bfgnet.cam2mqtt.mqtt.path.CameraPath

object CameraEventConverters
        extends AvailabilityEventConverter
                with MotionEventConverter {

    case class MqttBaseTopicProvider(base: String)

    implicit class CameraEventConv[EV <: CameraEvent](ev: EV) {
        def toMqtt(base: String)(implicit conv: EV => MqttMessage): MqttMessage = {
            val raw = conv(ev)
            raw.withTopic(s"$base/${raw.topic}")
        }

        def toMqtt()(implicit conv: EV => MqttMessage, prov: MqttBaseTopicProvider): MqttMessage = toMqtt(prov.base)

        def tryToMqtt()(implicit prov: MqttBaseTopicProvider): Option[MqttMessage] ={
            if (knownEvents.isDefinedAt(ev)) {
                Option(knownEvents.apply(ev))
            } else None
        }

        def tryToMqtt(base: String): Option[MqttMessage] = {
            implicit val prov: MqttBaseTopicProvider = MqttBaseTopicProvider(base)
            tryToMqtt()
        }

        private def knownEvents(implicit prov: MqttBaseTopicProvider): PartialFunction[CameraEvent, MqttMessage] = {
            case e: CameraAvailableEvent => e.toMqtt()
            case e: CameraMotionEvent => e.toMqtt()
        }
    }
}

private[converters] trait CameraEventConverter extends CameraPath {
}
