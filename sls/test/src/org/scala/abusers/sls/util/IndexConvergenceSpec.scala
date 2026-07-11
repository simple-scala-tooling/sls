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

import TestClient.awaitUntil

/** Convergence guards for the project index (`ai/test-plan.md` 3.2 / 3.3): a failed compile must not half-apply an
  * index update, and deleting a definition must remove it. Both are instances of the spine invariant — session state
  * must equal a fresh index of the final content.
  */
object IndexConvergenceSpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  /** Forwards to a CSP double, counting failed compiles: a failed compile (by design) leaves no other observable
    * trace, so tests await the counter instead of sleeping.
    */
  final class FailureCountingCsp(underlying: csp.CspServer[IO], failures: Ref[IO, Int]) extends csp.CspServer[IO] {
    def compile(
        scopeId: String,
        classpath: List[String],
        sourcePath: List[String],
        scalacOptions: List[String],
        javacOptions: List[String],
        scalaVersion: csp.ScalaVersion,
    ): IO[csp.CompileOutput] =
      underlying
        .compile(scopeId, classpath, sourcePath, scalacOptions, javacOptions, scalaVersion)
        .onError(_ => failures.update(_ + 1))
  }

  test("a failed compile preserves the last good index state (test-plan 3.2)") {
    // Note: getSymbolsByName is case-insensitive, so the method names must not collide with the class name.
    val src = code"""
      |package example
      |
      |class Foo {
      |  def ${m1}alpha${m2}: Int = 1
      |${m3}}
    """
    val harness = TestWorkspace.withSources("Foo.scala" -> src.text).flatMap { ws =>
      for {
        workDir  <- Resource.make(IO.blocking(os.temp.dir(prefix = "sls-test-csp-probe")))(d =>
          IO.blocking(os.remove.all(d))
        )
        failures <- Resource.eval(Ref.of[IO, Int](0))
        probe = new FailureCountingCsp(FakeCspServer.dotcBacked(workDir), failures)
        h <- TestServer.resource(ws, cspServer = Some(probe))
      } yield (h, failures)
    }
    harness.use { case (h, failures) =>
      val uri = h.workspace.uri("Foo.scala")
      for {
        _ <- h.client.openFile(uri, src.text)
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("alpha").map(s => Option.when(s.nonEmpty)(s)),
          label = "alpha to be indexed after the clean save",
        )
        // Rename alpha -> omega (same length, no position shift) and add a type error before the closing brace.
        _ <- h.client.editFile(uri, lsp.Range(src.at(m1), src.at(m2)), "omega")
        _ <- h.client.insertAt(uri, src.at(m3), "  val broken: Int = \"boom\"\n")
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(failures.get.map(n => Option.when(n >= 1)(n)), label = "the broken save to fail compilation")
        alphaAfterFail <- h.symbolIndex.getSymbolsByName("alpha")
        omegaAfterFail <- h.symbolIndex.getSymbolsByName("omega")
        // Remove the broken line: it occupies the line m3 pointed at; the brace moved one line down.
        errLine = src.at(m3).line
        _ <- h.client.editFile(uri, lsp.Range(lsp.Position(errLine, 0), lsp.Position(errLine + 1, 0)), "")
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("omega").map(s => Option.when(s.nonEmpty)(s)),
          label = "omega to be indexed after the fixed save",
        )
        alphaAfterFix  <- h.symbolIndex.getSymbolsByName("alpha")
        finalContent <- h.client.bufferOf(uri)
        freshIds     <- Convergence.freshIndexIds("Foo.scala" -> finalContent)
        sessionIds   <- Convergence.sessionIndexIds(h)
      } yield expect(alphaAfterFail.nonEmpty, "alpha gone after a failed compile — index was half-updated") &&
        expect(omegaAfterFail.isEmpty, "omega leaked into the index from a failed compile") &&
        expect(alphaAfterFix.isEmpty, "alpha survived the rename after the fixed save") &&
        expect.same(freshIds, sessionIds)
    }
  }

  test("deleting a definition removes it from the index (test-plan 3.3)") {
    val src = code"""
      |package example
      |
      |class Widgets {
      |  def keep: Int = 1
      |${m1}  def gone: Int = 2
      |${m2}}
    """
    TestWorkspace.withSources("Widgets.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      val uri = h.workspace.uri("Widgets.scala")
      for {
        _ <- h.client.openFile(uri, src.text)
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("gone").map(s => Option.when(s.nonEmpty)(s)),
          label = "gone to be indexed after the first save",
        )
        _ <- h.client.editFile(uri, lsp.Range(src.at(m1), src.at(m2)), "")
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("gone").map(s => Option.when(s.isEmpty)(())),
          label = "gone to disappear from the index after its definition was deleted",
        )
        keep         <- h.symbolIndex.getSymbolsByName("keep")
        finalContent <- h.client.bufferOf(uri)
        freshIds     <- Convergence.freshIndexIds("Widgets.scala" -> finalContent)
        sessionIds   <- Convergence.sessionIndexIds(h)
      } yield expect(keep.nonEmpty, "keep vanished alongside the deleted definition") &&
        expect.same(freshIds, sessionIds)
    }
  }
}
