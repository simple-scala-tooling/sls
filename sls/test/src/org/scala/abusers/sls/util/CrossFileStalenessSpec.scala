package org.scala.abusers.sls.util

import cats.effect.IO
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import TestClient.awaitUntil

/** Cross-file staleness (`ai/test-plan.md` 3.4), Metals' weakest spot: breaking B's API must surface diagnostics in
  * A — a file the user never touched — and fixing A must clear them. Stub-CSP variant: FakeCspServer recompiles the
  * whole workspace, so this guards the LSP-side plumbing (publishing for un-edited files); the real-zincCli variant
  * that guards the invalidation closure itself lands with H2 (`cspIt`).
  */
object CrossFileStalenessSpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  test("breaking B's signature surfaces diagnostics in A; fixing A clears them (test-plan 3.4)") {
    val a = code"""
      |package example
      |
      |object AUser {
      |  val n: ${m1}Int${m2} = Lib.value
      |}
    """
    val b = code"""
      |package example
      |
      |object Lib {
      |  def value: ${m1}Int = 1${m2}
      |}
    """
    TestWorkspace.withSources("A.scala" -> a.text, "B.scala" -> b.text).flatMap(TestServer.resource(_)).use { h =>
      val uriA = h.workspace.uri("A.scala")
      val uriB = h.workspace.uri("B.scala")
      for {
        _ <- h.client.openFile(uriA, a.text)
        _ <- h.client.openFile(uriB, b.text)
        _ <- h.client.saveFile(uriB)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("value").map(s => Option.when(s.nonEmpty)(s)),
          label = "the clean workspace to compile and index",
        )

        // Break B's API — A's val no longer typechecks. Only B is edited and saved.
        _       <- h.client.editFile(uriB, lsp.Range(b.at(m1), b.at(m2)), "String = \"s\"")
        _       <- h.client.saveFile(uriB)
        aBroken <- awaitUntil(
          h.client.latestDiagnostics(uriA).map(d => Option.when(d.nonEmpty)(d)),
          label = "diagnostics to appear in A after B's breaking change",
        )
        bDiags <- h.client.latestDiagnostics(uriB)

        // Fix A to match the new API; everything must clear.
        _ <- h.client.editFile(uriA, lsp.Range(a.at(m1), a.at(m2)), "String")
        _ <- h.client.saveFile(uriA)
        _ <- awaitUntil(
          h.client.latestDiagnostics(uriA).map(d => Option.when(d.isEmpty)(())),
          label = "A's diagnostics to clear after the fix",
        )
        bAfter <- h.client.latestDiagnostics(uriB)

        finalA     <- h.client.bufferOf(uriA)
        finalB     <- h.client.bufferOf(uriB)
        freshIds   <- Convergence.freshIndexIds("A.scala" -> finalA, "B.scala" -> finalB)
        sessionIds <- Convergence.sessionIndexIds(h)
      } yield expect(
        aBroken.exists(_.severity.contains(lsp.DiagnosticSeverity.ERROR)),
        s"expected an ERROR in the un-edited A, got $aBroken",
      ) &&
        expect(bDiags.isEmpty, s"B compiles fine and must stay clean, got $bDiags") &&
        expect(bAfter.isEmpty, s"B gained diagnostics after A's fix: $bAfter") &&
        expect.same(freshIds, sessionIds)
    }
  }
}
