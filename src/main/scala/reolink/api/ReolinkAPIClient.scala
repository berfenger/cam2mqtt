package net.bfgnet.cam2mqtt
package reolink.api

import reolink.ReolinkHost

import org.apache.pekko.actor.ClassicActorSystemProvider

import scala.concurrent.{ExecutionContext, Future}

trait ReolinkApiLoginState

trait ReolinkAPIClient[S <: ReolinkApiLoginState] {

    def login(host: ReolinkHost)
             (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[S]

    def reqPost(state: S, host: ReolinkHost, cmd: Option[String], body: String)
               (implicit _as: ClassicActorSystemProvider, _ec: ExecutionContext): Future[String]

}
