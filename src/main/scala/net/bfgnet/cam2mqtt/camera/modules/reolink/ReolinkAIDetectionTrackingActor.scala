package net.bfgnet.cam2mqtt.camera.modules.reolink

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.bfgnet.cam2mqtt.camera.CameraProtocol.{CameraCmd, WrappedModuleCmd}
import net.bfgnet.cam2mqtt.reolink.{GetAiObjectState, GetAiStateCmdResponse, ReolinkHost, ReolinkRequests}
import net.bfgnet.cam2mqtt.utils.ActorContextImplicits

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ReolinkAIDetectionTrackingActor extends ActorContextImplicits {

    case class AIDetectionState(key: String, alarmState: Boolean, isSupported: Boolean)

    sealed trait AITrackerCmd

    case object CheckAIState extends AITrackerCmd

    case class GotAIStates(state: List[AIDetectionState]) extends AITrackerCmd

    case class GotAIStateFailure(error: Option[Throwable]) extends AITrackerCmd

    case object Terminate extends AITrackerCmd

    case class ReolinkAIMotionDetectionStateUpdate(key: String, motion: Boolean)

    def apply(parent: ActorRef[CameraCmd], host: ReolinkHost): Behavior[AITrackerCmd] = {
        Behaviors.setup { implicit context =>
            running(parent, host, Nil)
        }
    }

    def running(parent: ActorRef[CameraCmd], host: ReolinkHost, states: List[AIDetectionState]): Behavior[AITrackerCmd] = {
        Behaviors.setup { implicit context =>
            val sched = context.scheduleOnce(2.seconds, context.self, CheckAIState)
            Behaviors.receiveMessagePartial {
                case CheckAIState =>
                    context.pipeToSelf(ReolinkRequests.getAiState(host)) {
                        case Success(Some(value)) => GotAIStates(convertAIDetectionStates(value))
                        case Failure(err) => GotAIStateFailure(Option(err))
                    }
                    context.setReceiveTimeout(1500.millis, GotAIStateFailure(None))
                    waitingForCommand(parent, host, states)
                case Terminate =>
                    sched.cancel()
                    notifyParentOnStatesOnTerminate(parent, states)
                    Behaviors.stopped
            }
        }
    }

    def waitingForCommand(parent: ActorRef[CameraCmd], host: ReolinkHost, states: List[AIDetectionState]): Behavior[AITrackerCmd] = {
        Behaviors.setup { implicit context =>
            Behaviors.receiveMessagePartial {
                case GotAIStates(newStates) =>
                    // check state changes to notify
                    val statesChanged = newStates
                            .filter(_.isSupported)
                            .map { ns => ns -> states.find(_.key == ns.key).map(_.alarmState != ns.alarmState) }
                            .filter { v => v._2.contains(true) || (v._2.isEmpty && v._1.alarmState) }
                            .map(_._1)
                    statesChanged.foreach { s =>
                        parent ! WrappedModuleCmd(ReolinkAIMotionDetectionStateUpdate(s.key, motion = s.alarmState))
                    }
                    // reschedule next event
                    running(parent, host, newStates)
                case GotAIStateFailure(_) =>
                    running(parent, host, states)
                case Terminate =>
                    notifyParentOnStatesOnTerminate(parent, states)
                    Behaviors.stopped
            }
        }
    }

    private def notifyParentOnStatesOnTerminate(parent: ActorRef[CameraCmd], states: List[AIDetectionState])(implicit _context: ActorContext[AITrackerCmd]) = {
        states.filter(_.alarmState).foreach { ev =>
            parent ! WrappedModuleCmd(ReolinkAIMotionDetectionStateUpdate(ev.key, motion = false))
        }
    }

    private def convertAIDetectionState(key: String, r: GetAiObjectState): AIDetectionState = {
        AIDetectionState(key, r.isDetected, r.isSupported)
    }

    private def convertAIDetectionStates(r: GetAiStateCmdResponse): List[AIDetectionState] = {
        List(convertAIDetectionState("people", r.value.people), convertAIDetectionState("vehicle", r.value.vehicle),
            convertAIDetectionState("pet", r.value.dog_cat), convertAIDetectionState("face", r.value.face))
    }
}
