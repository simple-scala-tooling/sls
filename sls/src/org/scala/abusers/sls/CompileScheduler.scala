package org.scala.abusers.sls

import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.effect.IO
import cats.effect.Ref

/** Per-key conflating scheduler.
  *
  * At most one task per key runs at a time. While a key's task is running, further `schedule` calls for that key are
  * conflated into a single pending re-run that keeps only the *newest* task; intermediate tasks are dropped. When the
  * running task finishes, the pending one (if any) runs next.
  *
  * Each key has a `circularBuffer(1)` mailbox drained by one long-lived worker fiber: offering the newest task evicts
  * any older queued one, so the buffer always holds the latest save. (`Queue.dropping` would be wrong here — it keeps
  * the *oldest* offer and discards new ones, so the most recent save would never compile.) A worker fiber per key stays
  * parked on `take` for the lifetime of the supervisor; the keys are the project's build targets, so this is a small,
  * bounded number of idle fibers rather than per-save churn.
  *
  * Used to collapse a burst of `didSave` compiles for one build target into "the in-flight compile + at most one more"
  * instead of one full compile per save. The zinc-cli side still serializes same-scope compiles as a safety net; this
  * just stops us from queueing redundant work in the first place.
  */
class CompileScheduler private (state: Ref[IO, Map[String, Queue[IO, IO[Unit]]]], supervisor: Supervisor[IO]) {

  def schedule(key: String)(task: IO[Unit]): IO[Unit] =
    Queue.circularBuffer[IO, IO[Unit]](capacity = 1).flatMap { fresh =>
      state.modify { m =>
        m.get(key) match {
          case Some(existing) => (m, existing.offer(task))
          case None           =>
            (m.updated(key, fresh), fresh.offer(task) *> supervisor.supervise(runLoop(fresh)).void)
        }
      }.flatten
    }

  private def runLoop(mailbox: Queue[IO, IO[Unit]]): IO[Unit] =
    mailbox.take.flatMap(_.attempt.void).foreverM
}

object CompileScheduler {
  def create(supervisor: Supervisor[IO]): IO[CompileScheduler] =
    Ref.of[IO, Map[String, Queue[IO, IO[Unit]]]](Map.empty).map(new CompileScheduler(_, supervisor))
}
