package org.scala.abusers.sls.util

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.effect.Ref
import org.scala.abusers.csp
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import scala.concurrent.duration.*

import TestClient.awaitUntil

/** Regression guard for the rapid edit-save-edit-save bug: two `didSave`s for the same target used to fork two compile
  * fibers that raced into the (shared, per-scope) zinc analysis store and output jar at the same time. The per-target
  * conflating [[org.scala.abusers.sls.CompileScheduler]] must keep them from overlapping — the in-flight compile
  * finishes before the next one starts.
  *
  * The CSP double here records peak concurrency instead of compiling anything: each `compile` widens its window with a
  * sleep so an overlap would be observed if one existed.
  */
object DidSaveCompileConcurrencySpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  /** A CSP double that only measures overlap. `started` lets a test await N compiles; `maxConcurrent` is the peak
    * number seen running at once — it must stay 1 if compiles are properly serialized per scope.
    */
  final class ConcurrencyProbeCsp(
      inFlight: Ref[IO, Int],
      maxConcurrent: Ref[IO, Int],
      started: Ref[IO, Int],
      window: FiniteDuration,
  ) extends csp.CspServer[IO] {
    def compile(
        scopeId: String,
        classpath: List[String],
        sourcePath: List[String],
        scalacOptions: List[String],
        javacOptions: List[String],
        scalaVersion: csp.ScalaVersion,
    ): IO[csp.CompileOutput] =
      Resource
        .make(
          started.update(_ + 1) *>
            inFlight.updateAndGet(_ + 1).flatMap(n => maxConcurrent.update(_ max n))
        )(_ => inFlight.update(_ - 1))
        .surround(IO.sleep(window))
        // Nonexistent jar: updateOutputClasspath skips (no such file) and onCompilationComplete swallows the
        // indexing error — this probe is only about the compile boundary, not downstream indexing.
        .as(csp.CompileOutput("/nonexistent/sls-probe.jar", changedFiles = Map.empty, csp.OutputFormat.TASTY))
  }

  test("rapid successive saves of the same target never overlap compiles") {
    val src = code"""
      |package example
      |
      |class Foo {
      |${m1}}
    """
    val program = for {
      inFlight      <- Ref.of[IO, Int](0)
      maxConcurrent <- Ref.of[IO, Int](0)
      started       <- Ref.of[IO, Int](0)
      probe = new ConcurrencyProbeCsp(inFlight, maxConcurrent, started, window = 300.millis)
      result <- TestWorkspace
        .withSources("Foo.scala" -> src.text)
        .flatMap(ws => TestServer.resource(ws, cspServer = Some(probe)))
        .use { h =>
          val uri = h.workspace.uri("Foo.scala")
          for {
            _ <- h.client.openFile(uri, src.text)
            // Two edits saved back-to-back: the second save lands while the first compile is still in its window.
            _    <- h.client.insertAt(uri, src.at(m1), "  def a: Int = 1\n")
            _    <- h.client.saveFile(uri)
            _    <- h.client.insertAt(uri, src.at(m1), "  def b: Int = 2\n")
            _    <- h.client.saveFile(uri)
            _    <- awaitUntil(started.get.map(n => Option.when(n >= 2)(n)), label = "both saves to reach the CSP")
            peak <- maxConcurrent.get
          } yield expect(peak == 1)
        }
    } yield result
    program
  }
}
