package org.scala.abusers.sls

import cats.effect.std.Semaphore
import cats.effect.IO
import cats.effect.std.Queue
import cats.data.Chain
import cats.effect.Ref

sealed trait SynchronizedState

trait ComputationQueue {
  protected given SynchronizedState = new SynchronizedState {}

  def synchronously[A](computation: SynchronizedState ?=> IO[A]): IO[A]
  def pushSync(computation: IO[Unit]): IO[Unit]
}

class ComputationQueueImpl(semaphore: Semaphore[IO], pendingSyncs: Ref[IO, Chain[IO[Unit]]]) extends ComputationQueue {
  def synchronously[A](computation: SynchronizedState ?=> IO[A]): IO[A] =
    semaphore.permit.surround(pendingSyncs.getAndSet(Chain.nil).flatMap(_.sequence_) >> computation)

  def pushSync(computation: IO[Unit]): IO[Unit] = pendingSyncs.update(_.append(computation))
}

object ComputationQueue {
  def instance: IO[ComputationQueue] =
    for {
      semaphore    <- Semaphore[IO](1)
      ref          <- Ref.of[IO, Chain[IO[Unit]]](Chain.empty)
    } yield ComputationQueueImpl(semaphore, ref)
}
