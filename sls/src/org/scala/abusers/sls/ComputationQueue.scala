package org.scala.abusers.sls

import cats.effect.IO
import cats.effect.std.Semaphore

sealed trait SynchronizedState

trait ComputationQueue {
  protected given SynchronizedState = new SynchronizedState {}
  def synchronously[A](computation: SynchronizedState ?=> IO[A]): IO[A]
}

class ComputationQueueImpl(semaphore: Semaphore[IO]) extends ComputationQueue {
  def synchronously[A](computation: SynchronizedState ?=> IO[A]): IO[A] = {
    semaphore.permit.use(_ => computation)
  }
}

object ComputationQueue {
  def instance: IO[ComputationQueue] =
    Semaphore[IO](1).map(ComputationQueueImpl.apply)
}
