package org.scala.abusers.sls.util

import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import org.scala.abusers.csp
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import TestClient.awaitUntil

/** Save conflation (`ai/test-plan.md` 3.6): a burst of saves against one target must collapse to "the in-flight
  * compile + exactly one more" (newest wins, intermediates dropped — the CompileScheduler contract), and the final
  * state must converge on the last save's content.
  */
object SaveConflationSpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  /** Holds every compile at `gate` until it is released; counts entries and completions so the test can await both
    * "first compile is in flight" and "all compiles finished" without sleeping.
    */
  final class GatedCsp(
      underlying: csp.CspServer[IO],
      gate: Deferred[IO, Unit],
      entered: Ref[IO, Int],
      completed: Ref[IO, Int],
  ) extends csp.CspServer[IO] {
    def compile(
        scopeId: String,
        classpath: List[String],
        sourcePath: List[String],
        scalacOptions: List[String],
        javacOptions: List[String],
        scalaVersion: csp.ScalaVersion,
    ): IO[csp.CompileOutput] =
      entered.update(_ + 1) *> gate.get *>
        underlying.compile(scopeId, classpath, sourcePath, scalacOptions, javacOptions, scalaVersion) <*
        completed.update(_ + 1)
  }

  test("a burst of saves conflates to at most two compiles, newest content wins (test-plan 3.6)") {
    val src = code"""
      |package example
      |
      |class App {
      |${m1}}
    """
    val harness = TestWorkspace.withSources("App.scala" -> src.text).flatMap { ws =>
      for {
        workDir   <- Resource.make(IO.blocking(os.temp.dir(prefix = "sls-test-csp-gate")))(d =>
          IO.blocking(os.remove.all(d))
        )
        gate      <- Resource.eval(Deferred[IO, Unit])
        entered   <- Resource.eval(Ref.of[IO, Int](0))
        completed <- Resource.eval(Ref.of[IO, Int](0))
        probe = new GatedCsp(FakeCspServer.dotcBacked(workDir), gate, entered, completed)
        h <- TestServer.resource(ws, cspServer = Some(probe))
      } yield (h, gate, entered, completed)
    }
    harness.use { case (h, gate, entered, completed) =>
      val uri = h.workspace.uri("App.scala")
      for {
        _ <- h.client.openFile(uri, src.text)
        // First save: its compile enters the probe and parks at the gate.
        _ <- h.client.insertAt(uri, src.at(m1), "  def v1: Int = 1\n")
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(entered.get.map(n => Option.when(n >= 1)(n)), label = "the first compile to park at the gate")
        // Four more edit+save rounds while the first compile is parked: the scheduler must conflate them.
        _ <- (2 to 5).toList.traverse_ { i =>
          h.client.insertAt(uri, src.at(m1), s"  def v$i: Int = $i\n") *> h.client.saveFile(uri)
        }
        _ <- gate.complete(())
        _ <- awaitUntil(
          completed.get.map(n => Option.when(n >= 2)(n)),
          label = "the in-flight compile and the single conflated one to finish",
        )
        // v5 was on disk before the gate opened, so even the first compile picked it up; the index converges on it.
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("v5").map(s => Option.when(s.nonEmpty)(s)),
          label = "v5 to be indexed",
        )
        compiles     <- entered.get
        finalContent <- h.client.bufferOf(uri)
        freshIds     <- Convergence.freshIndexIds("App.scala" -> finalContent)
        sessionIds   <- Convergence.sessionIndexIds(h)
      } yield expect(compiles == 2, s"expected 2 compiles (in-flight + conflated newest), got $compiles") &&
        expect.same(freshIds, sessionIds)
    }
  }
}
