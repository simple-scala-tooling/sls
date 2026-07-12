package org.scala.abusers.sls.util

import cats.effect.IO
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import TestClient.awaitUntil

/** The most visible LSP failure mode (`ai/test-plan.md` 3.1): diagnostics must appear when a save breaks compilation
  * and — the part that regresses in the wild — must clear once the code is fixed. Exercises the full CSP contract:
  * per-file publishDiagnostics through ZincCspClient/DiagnosticManager, with the empty publish clearing the file.
  */
object DiagnosticsLifecycleSpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  test("diagnostics appear on a broken save and clear on the fixed one (test-plan 3.1)") {
    val src = code"""
      |package example
      |
      |class Foo {
      |  def alpha: Int = 1
      |${m1}}
    """
    TestWorkspace.withSources("Foo.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      val uri     = h.workspace.uri("Foo.scala")
      val errLine = src.at(m1).line
      for {
        _ <- h.client.openFile(uri, src.text)
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("alpha").map(s => Option.when(s.nonEmpty)(s)),
          label = "the clean save to compile and index",
        )
        afterClean <- h.client.latestDiagnostics(uri)

        _      <- h.client.insertAt(uri, src.at(m1), "  val broken: Int = \"boom\"\n")
        _      <- h.client.saveFile(uri)
        broken <- awaitUntil(
          h.client.latestDiagnostics(uri).map(d => Option.when(d.nonEmpty)(d)),
          label = "diagnostics to appear after the broken save",
        )

        _ <- h.client.editFile(uri, lsp.Range(lsp.Position(errLine, 0), lsp.Position(errLine + 1, 0)), "")
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.client.latestDiagnostics(uri).map(d => Option.when(d.isEmpty)(())),
          label = "diagnostics to clear after the fixed save",
        )
      } yield expect(afterClean.isEmpty, s"clean save produced diagnostics: $afterClean") &&
        expect(broken.exists(_.severity.contains(lsp.DiagnosticSeverity.ERROR)), s"no ERROR diagnostic in $broken") &&
        expect(
          broken.exists(_.range.start.line == errLine),
          s"diagnostic not anchored to the broken line $errLine: ${broken.map(_.range)}",
        )
    }
  }
}
