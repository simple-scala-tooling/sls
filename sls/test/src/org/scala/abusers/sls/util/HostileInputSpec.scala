package org.scala.abusers.sls.util

import cats.effect.IO
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.scala.abusers.sls.SynchronizedState
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import TestClient.awaitUntil

/** Hostile-input armor (`ai/test-plan.md` 3.11): malformed LSP traffic must produce an error or a safe no-op — never
  * a hang, a corrupted buffer, or a dead server. Raw params go straight to the server, bypassing TestClient's
  * well-behaved helpers. Pinned policies: out-of-bounds positions clamp to the document (LSP behavior), inverted
  * ranges fail the didChange atomically.
  */
object HostileInputSpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  private def rawChange(uri: String, version: Int, range: lsp.Range, text: String): lsp.DidChangeTextDocumentParams =
    lsp.DidChangeTextDocumentParams(
      lsp.VersionedTextDocumentIdentifier(uri = uri, version = version),
      contentChanges = List(
        lsp.TextDocumentContentChangeEvent
          .Case0Case(lsp.TextDocumentContentChangePartial(range = range, text = text))
          .asInstanceOf[lsp.TextDocumentContentChangeEvent]
      ),
    )

  test("malformed traffic errors or no-ops; the server keeps working (test-plan 3.11)") {
    val foo = code"""
      |package example
      |
      |class Foo {
      |${m1}}
    """
    val hostile = code"""
      |package example
      |
      |class Hostile {
      |  def h: Int = 1
      |}
    """
    TestWorkspace
      .withSources("Foo.scala" -> foo.text, "Hostile.scala" -> hostile.text)
      .flatMap(TestServer.resource(_))
      .use { h =>
        given SynchronizedState = TestComputationQueue().unsafeGetState
        val fooUri              = h.workspace.uri("Foo.scala")
        val hostileUri          = h.workspace.uri("Hostile.scala")
        val hostileLsp          = hostileUri.toLspUri
        for {
          _ <- h.client.openFile(fooUri, foo.text)
          _ <- h.client.openFile(hostileUri, hostile.text)

          // 1. range far past EOF: clamps to document end, never corrupts
          pastEof <- h.server
            .textDocumentDidChange(
              rawChange(hostileLsp, 1, lsp.Range(lsp.Position(9999, 42), lsp.Position(9999, 50)), "\n// appended")
            )
            .attempt
          afterPastEof <- h.textDocumentSync.get(hostileUri)

          // 2. inverted range: rejected, document unchanged
          inverted <- h.server
            .textDocumentDidChange(
              rawChange(hostileLsp, 2, lsp.Range(lsp.Position(3, 5), lsp.Position(1, 0)), "boom")
            )
            .attempt
          afterInverted <- h.textDocumentSync.get(hostileUri)

          // 3. negative position: clamps to document start
          negative <- h.server
            .textDocumentDidChange(
              rawChange(hostileLsp, 3, lsp.Range(lsp.Position(-5, -7), lsp.Position(-5, -7)), "// first\n")
            )
            .attempt

          // 4. didChange for a never-opened uri: errors, no hang
          ghost <- h.server
            .textDocumentDidChange(
              rawChange("file:///nowhere/Ghost.scala", 1, lsp.Range(lsp.Position(0, 0), lsp.Position(0, 0)), "x")
            )
            .attempt

          // 5. double didClose: second close is a safe no-op
          _      <- h.client.closeFile(hostileUri)
          reclose <- h.server
            .textDocumentDidClose(lsp.DidCloseTextDocumentParams(lsp.TextDocumentIdentifier(hostileLsp)))
            .attempt

          // afterlife: a well-formed flow still works end to end
          _ <- h.client.insertAt(fooUri, foo.at(m1), "  def alive: Int = 1\n")
          _ <- h.client.saveFile(fooUri)
          _ <- awaitUntil(
            h.symbolIndex.getSymbolsByName("alive").map(s => Option.when(s.nonEmpty)(s)),
            label = "a well-formed save to compile and index after the hostile traffic",
          )
        } yield expect(pastEof.isRight, s"past-EOF change should clamp, got $pastEof") &&
          expect(
            afterPastEof.content.endsWith("// appended"),
            s"clamped insert should land at document end, got: ${afterPastEof.content.takeRight(30)}",
          ) &&
          expect(inverted.isLeft, "inverted range must be rejected") &&
          expect.same(afterPastEof.content, afterInverted.content) &&
          expect(negative.isRight, s"negative position should clamp, got $negative") &&
          expect(ghost.isLeft, "didChange for a never-opened uri must error") &&
          expect(reclose.isRight, s"double didClose should be a no-op, got $reclose")
      }
  }
}
