package org.scala.abusers.sls.index

import cats.effect.IO
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.FiniteDuration

enum IndexPhase {
  case Cold, Bootstrapping, Ready, Failed
}

/** Tracks the indexer's lifecycle phase so query-side code can decide whether to wait, degrade, or proceed.
  *
  * The state machine is intentionally minimal: `Cold` (initial), `Bootstrapping` (first-pass indexing in flight),
  * `Ready` (initial indexing finished — incremental updates still happen, but queries are well-defined),
  * `Failed` (bootstrap raised — terminal until process restart). No `Refreshing` phase: on-save updates are fast
  * enough to query against the current state without a phase flip. `Ready` and `Failed` are sticky terminals —
  * [[awaitReady]] short-circuits on either.
  */
class IndexLifecycle private (state: SignallingRef[IO, IndexPhase]) {

  def phase: IO[IndexPhase] = state.get

  /** Wait until the index reaches a terminal phase (`Ready` or `Failed`). Returns immediately if already there.
    * On timeout, returns the current phase so the caller can decide what to do (return partial results, surface
    * an indexing-in-progress message, etc.).
    */
  def awaitReady(timeout: FiniteDuration): IO[Either[IndexPhase, Unit]] =
    state.waitUntil(p => p == IndexPhase.Ready || p == IndexPhase.Failed).timeoutTo(timeout, IO.unit) *>
      state.get.map {
        case IndexPhase.Ready => Right(())
        case other            => Left(other)
      }

  def transition(next: IndexPhase): IO[Unit] = state.set(next)

  /** Atomically transition Cold → Bootstrapping. Returns `true` if this caller won the race and should run the
    * bootstrap work; `false` if another caller already did (or the lifecycle is past Cold).
    */
  def tryStartBootstrap: IO[Boolean] =
    state.modify {
      case IndexPhase.Cold => (IndexPhase.Bootstrapping, true)
      case other           => (other, false)
    }
}

object IndexLifecycle {
  def empty: IO[IndexLifecycle] =
    SignallingRef.of[IO, IndexPhase](IndexPhase.Cold).map(new IndexLifecycle(_))
}
