package net.bfgnet.cam2mqtt
package camera

import camera.CameraConfig.CameraInfo
import camera.CameraProtocol._
import camera.modules.CameraModules
import camera.modules.reolink.ReolinkModule
import eventbus.CameraEventBus

import akka.actor.ActorSystem
import akka.actor.typed._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import scala.concurrent.duration._

object Camera {

    def apply(info: CameraInfo) = starting(info)

    private def starting(info: CameraInfo): Behavior[CameraCmd] = {
        Behaviors.supervise[CameraCmd] {
            Behaviors.setup { implicit context =>
                implicit val _as: ActorSystem = context.system.toClassic
                val modules = modulesForCamera(info)
                val children = modules.map { case (cfg, m) =>
                    m.moduleId -> context.spawn(m.createBehavior(context.self, info, cfg), s"mod_${m.moduleId}")
                }.toMap
                // watch children
                children.values.foreach(context.watch)
                routing(info, children, available = false)
            }
        }.onFailure(SupervisorStrategy.restartWithBackoff(minBackoff = 4.seconds, maxBackoff = 1.minute, randomFactor = 0.2))
    }

    private def routing(info: CameraInfo, modules: Map[String, ActorRef[CameraCmd]], available: Boolean)(implicit context: ActorContext[CameraCmd]): Behavior[CameraCmd] = {
        Behaviors.receiveMessagePartial[CameraCmd] {
            case ev@CameraModuleMessage(camId, modId, _) if info.cameraId == camId && modules.contains(modId) =>
                modules(modId) ! ev
                Behaviors.same
            case ev@CameraModuleAction(id, mId, cmd) if id == info.cameraId && modules.contains(mId) =>
                modules(mId) ! ev
                Behaviors.same
            case ev@CameraModuleAction(id, mId, cmd) if id == info.cameraId && !modules.contains(mId) =>
                context.log.debug(s"module ${mId} is not enabled for this camera")
                Behaviors.same
            case CameraModuleEvent(_, _, ev: CameraMotionEvent) =>
                // if motion state does not change, discard event
                CameraEventBus.bus.publish(ev)
                // send motion event to Reolink module if available (for AI detection integration)
                modules.get(ReolinkModule.moduleId).foreach(_ ! CameraModuleEvent(ev.cameraId, ev.moduleId, ev))
                Behaviors.same
            case CameraModuleEvent(_, _, ev: CameraObjectDetectionEvent) =>
                CameraEventBus.bus.publish(ev)
                Behaviors.same
            case CameraModuleEvent(_, _, ev: CameraAvailableEvent) if available != ev.available =>
                // if available state does not change, discard event
                if (available != ev.available) {
                    CameraEventBus.bus.publish(ev)
                    routing(info, modules, ev.available)
                } else Behaviors.same
            case CameraModuleEvent(_, _, ev) =>
                // for other module events, just redirect
                CameraEventBus.bus.publish(ev)
                Behaviors.same
            case TerminateCam =>
                // Stop children modules
                modules.values.foreach(_ ! TerminateCam)
                finishing(info.cameraId, modules.values.toList)
        }.receiveSignal {
            case (_, Terminated(_)) =>
                throw new Exception("escalate failure")
            case (_, PreRestart) =>
                CameraEventBus.bus.publish(CameraAvailableEvent(info.cameraId, available = false))
                Behaviors.same
        }
    }

    private def finishing(cameraId: String, modules: List[ActorRef[CameraCmd]]): Behavior[CameraCmd] = {
        Behaviors.receiveSignal {
            case (_, Terminated(a)) =>
                val remaining = modules.filterNot(_ == a)
                if (remaining.nonEmpty)
                    finishing(cameraId, remaining)
                else {
                    CameraEventBus.bus.publish(CameraAvailableEvent(cameraId, available = false))
                    Behaviors.stopped
                }
        }
    }

    private def modulesForCamera(info: CameraInfo) =
        info.modules
                .map(c => c -> CameraModules.MODULES.find(_.moduleId == c.moduleId))
                .flatMap {
                    case (c, Some(m)) => Some(c -> m)
                    case _ => None
                }
}
