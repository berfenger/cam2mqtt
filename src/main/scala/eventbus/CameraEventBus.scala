package net.bfgnet.cam2mqtt
package eventbus

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.event.{EventBus, LookupClassification}
import camera.CameraProtocol.{CameraAvailableEvent, CameraEvent, CameraMotionEvent, CameraObjectDetectionEvent}
import eventbus.CameraEventBus._

object CameraEventBus {

    val TOPIC_AVAILABILITY = "av"
    val TOPIC_MOTION = "motion"
    val TOPIC_OBJECT_DETECTION = "object_detection"
    val TOPIC_OTHER = "oth"

    lazy val bus = new CameraEventBus()
}

case class EventTopicEnvelope(topic: String, msg: CameraEvent)

class CameraEventBus extends EventBus with LookupClassification {
    override type Event = EventTopicEnvelope
    override type Classifier = String
    override type Subscriber = ActorRef

    override protected def compareSubscribers(a: ActorRef, b: ActorRef): Int = a.compareTo(b)

    override protected def classify(event: EventTopicEnvelope): String = event.topic

    override protected def publish(event: EventTopicEnvelope, subscriber: ActorRef): Unit = subscriber ! event.msg

    def publish(event: CameraMotionEvent): Unit = this.publish(EventTopicEnvelope(TOPIC_MOTION, event))

    def publish(event: CameraObjectDetectionEvent): Unit = this.publish(EventTopicEnvelope(TOPIC_OBJECT_DETECTION, event))

    def publish(event: CameraAvailableEvent): Unit = this.publish(EventTopicEnvelope(TOPIC_AVAILABILITY, event))

    def publish(event: CameraEvent): Unit = this.publish(EventTopicEnvelope(TOPIC_OTHER, event))

    override protected def mapSize() = 16

}
