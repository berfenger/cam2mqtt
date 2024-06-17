package net.bfgnet.cam2mqtt
package utils

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.scaladsl.ActorContext

import scala.concurrent.ExecutionContextExecutor

trait ActorContextImplicits {
    protected implicit def ecFromActorContext(implicit _ctxt: ActorContext[_]): ExecutionContextExecutor = _ctxt.executionContext

    protected implicit def casFromActorContext(implicit _ctxt: ActorContext[_]): ClassicActorSystemProvider = _ctxt.system
}

object ActorContextImplicits {
    implicit def ecFromActorContext(implicit _ctxt: ActorContext[_]): ExecutionContextExecutor = _ctxt.executionContext

    implicit def casFromActorContext(implicit _ctxt: ActorContext[_]): ClassicActorSystemProvider = _ctxt.system
}
