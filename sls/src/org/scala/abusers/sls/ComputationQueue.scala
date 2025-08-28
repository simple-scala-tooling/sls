package org.scala.abusers.sls

import cats.effect.IO
import cats.effect.std.Semaphore

trait SynchronizedState

class ComputationQueue(semaphore: Semaphore[IO]) {
  given SynchronizedState = new SynchronizedState {}
  def synchronously[A](computation: SynchronizedState ?=> IO[A]): IO[A] = {
    semaphore.permit.use(_ => computation(using new SynchronizedState {}))
  }
}

object ComputationQueue {
  def instance: IO[ComputationQueue] =
    Semaphore[IO](1).map(ComputationQueue.apply)
}
