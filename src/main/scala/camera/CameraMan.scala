package net.bfgnet.cam2mqtt
package camera

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}
import camera.CameraConfig.CameraInfo
import camera.CameraManProtocol.{CameraManCmd, _}
import camera.CameraProtocol._

object CameraMan {

    def apply(cameras: List[CameraInfo]): Behavior[CameraManCmd] = initFromConfig(cameras)

    private def initFromConfig(cameras: List[CameraInfo]): Behavior[CameraManCmd] =
        Behaviors.setup { context =>
            context.setLoggerName(CameraMan.getClass)
            context.log.debug(s"Starting cameraMan from config")

            // init cameras from config
            cameras.foreach(context.self ! InitCam(_))

            running(cameras, Map())
        }

    private def running(cameras: List[CameraInfo], monitored: Map[String, ActorRef[CameraCmd]]): Behavior[CameraManCmd] =
        Behaviors.setup { context =>

            Behaviors.receiveMessagePartial {
                case InitCam(cam) =>
                    context.log.info(s"Starting cam ${cam.copyWithPrivacy()}")
                    if (!monitored.contains(cam.cameraId)) {
                        val ref = context.spawn(Camera(cam), s"cam_${cam.cameraId}")
                        context.watch(ref)
                        running(cameras, monitored + (cam.cameraId -> ref))
                    } else {
                        context.log.warn(s"Cam ${cam.cameraId} already tracked")
                        Behaviors.same
                    }
                case RouteCameraCommand(id, msg) if monitored.contains(id) =>
                    monitored(id) ! msg
                    Behaviors.same
                case RouteCameraCommand(id, _) if !monitored.contains(id) =>
                    context.log.debug(s"camera $id does not exist")
                    Behaviors.same
                case Terminate =>
                    monitored.values.foreach(_ ! TerminateCam)
                    finishing(monitored.values.toList)
            }
        }

    private def finishing(monitored: List[ActorRef[CameraCmd]]): Behavior[CameraManCmd] =
        Behaviors.receiveSignal {
            case (_, Terminated(a)) =>
                val remaining = monitored.filterNot(_ == a)
                if (remaining.nonEmpty)
                    finishing(remaining)
                else {
                    Behaviors.stopped
                }
        }
}

