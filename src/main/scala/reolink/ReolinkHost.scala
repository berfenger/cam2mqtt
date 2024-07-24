package net.bfgnet.cam2mqtt
package reolink

import reolink.api._

import org.apache.pekko.actor.ClassicActorSystemProvider

import scala.concurrent.{ExecutionContext, Future}

case class ReolinkHost(host: String, port: Int, username: String, password: String, ssl: Boolean)

trait ReolinkHostAPIClient extends ReolinkHttpUtils {

    def parseCommandResponse(resp: String): ReolinkCmdResponse =
        OM.readValue(axeArray(resp), classOf[ReolinkCmdResponse])

    def reqPost(cmd: Option[String], body: String)(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String]
}

abstract class ReolinkHostWithClient[T <: ReolinkApiLoginState] extends ReolinkAPIClient[T]
    with ReolinkHostAPIClient with ReolinkCommands with ReolinkCapabilityCommands {
    val host: ReolinkHost
    var state: Option[T]

    protected def isStateValid(state: T): Boolean

    override def reqPost(cmd: Option[String], body: String)(implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String] = {
        state.filter(isStateValid) match {
            case Some(validState) =>
                reqPost(validState, host, cmd, body)
            case None =>
                for {
                    newState <- login(host)
                    _ = state = Option(newState)
                    r <- reqPost(newState, host, cmd, body)
                } yield r
        }
    }
}

case class ReolinkHostWithEncryptedAPIClient(override val host: ReolinkHost, override var state: Option[EncryptedLoginState])
    extends ReolinkHostWithClient[EncryptedLoginState] with EncryptedReolinkAPIClient {
    override protected def isStateValid(state: EncryptedLoginState): Boolean =
        state.expiryTimestamp < System.currentTimeMillis()
}

case class ReolinkHostWithPlainAPIClient(override val host: ReolinkHost, override var state: Option[PlainLoginState])
    extends ReolinkHostWithClient[PlainLoginState] with PlainReolinkAPIClient {
    override protected def isStateValid(state: PlainLoginState): Boolean = true
}
