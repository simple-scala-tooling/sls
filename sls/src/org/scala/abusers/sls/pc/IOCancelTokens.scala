package org.scala.abusers.pc

import cats.effect.kernel.Resource
import cats.effect.kernel.Resource.ExitCase
import cats.effect.std.Dispatcher
import cats.effect.IO
import org.slf4j.LoggerFactory

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import scala.meta.pc.CancelToken

trait IOCancelTokens {
  def mkCancelToken: Resource[IO, CancelToken]
}

object IOCancelTokens {
  val logger = LoggerFactory.getLogger(getClass)
  def instance: Resource[IO, IOCancelTokens] = Dispatcher.parallel[IO].map { dispatcher =>
    new {
      def mkCancelToken: Resource[IO, CancelToken] =
        IO.deferred[Boolean].toResource.flatMap { isCancelled =>
          val token: CancelToken = new {
            val onCancelVal: CompletableFuture[java.lang.Boolean] = dispatcher.unsafeToCompletableFuture(
              isCancelled.get.map(res => java.lang.Boolean(res))
            )

            def checkCanceled(): Unit =
              if onCancelVal.getNow(false) then {
                logger.info(s"Throwing CancellationException")
                throw new CancellationException()
              }

            def onCancel(): CompletionStage[java.lang.Boolean] = onCancelVal
          }

          Resource.makeCase(IO.pure(token)) {
            case (_, ExitCase.Canceled) =>
              IO(logger.info(s"Cancellation triggered")) >> isCancelled.complete(true).void
            case _ => isCancelled.complete(false).void
          }
        }
    }
  }
}
