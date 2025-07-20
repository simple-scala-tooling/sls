package org.scala.abusers.sls

import cats.effect.kernel.Fiber
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Temporal
import cats.syntax.all.*

import scala.concurrent.duration.FiniteDuration

class Debouncer(delay: FiniteDuration) {

  private val ref = Ref.unsafe[IO, Option[Fiber[IO, Throwable, Unit]]](None)

  def debounce(run: => IO[Unit]): IO[Unit] =
    for {
      fiberOpt <- ref.getAndSet(None)
      _        <- fiberOpt.traverse_(_.cancel)
      fiber <- Temporal[IO].start(
        IO.sleep(delay) *> run
      )
      _ <- ref.set(Some(fiber))
    } yield ()

}
