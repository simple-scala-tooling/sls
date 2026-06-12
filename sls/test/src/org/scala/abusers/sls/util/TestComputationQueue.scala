package org.scala.abusers.sls.util

import cats.effect.IO
import org.scala.abusers.sls.ComputationQueue
import org.scala.abusers.sls.SynchronizedState

/** Passthrough queue for tests: computations run unserialized and pending syncs are dropped.
  *
  * Dropping `pushSync` means `ServerImpl`'s deferred classpath swap (delete classesDir + unzip output jar) never
  * runs under this queue — deliberate, so tests never write into `./.sls/`.
  */
class TestComputationQueue extends ComputationQueue {
  override def synchronously[A](computation: (SynchronizedState) ?=> IO[A]): IO[A] = computation
  def unsafeGetState: SynchronizedState                                            = summon[SynchronizedState]
  override def pushSync(computation: IO[Unit]): IO[Unit]                           = IO.unit
}
