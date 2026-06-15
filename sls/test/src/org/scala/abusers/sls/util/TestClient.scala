package org.scala.abusers.sls.util

import cats.effect.kernel.Deferred
import cats.effect.IO
import cats.effect.Ref
import org.scala.abusers.sls.SlsLanguageClient
import org.scala.abusers.sls.SlsLanguageServer
import org.scala.abusers.sls.SourceUri

import scala.concurrent.duration.*

/** In-process LSP client double (Layer 3 of the test architecture, see `ai/test-infrastructure.md`).
  *
  * Implements the smithy-generated [[SlsLanguageClient]] — capturing diagnostics and window messages the server
  * pushes — and offers editor-simulation verbs that reproduce VS Code's event grammar: incremental `didChange`
  * ranges against a client-side buffer, and the buffer written to disk *before* `didSave` is sent (editors save the
  * file themselves; the notification only reports it).
  */
class TestClient private (
    serverRef: Deferred[IO, SlsLanguageServer[IO]],
    buffers: Ref[IO, Map[SourceUri, TestClient.Buffer]],
    diagnostics: Ref[IO, Map[String, List[lsp.Diagnostic]]],
    messages: Ref[IO, List[String]],
) extends SlsLanguageClient[IO] {

  // --- server → client notifications (captured for assertions) ---

  def textDocumentPublishDiagnostics(params: lsp.PublishDiagnosticsParams): IO[Unit] =
    diagnostics.update(_.updated(params.uri, params.diagnostics))

  def windowLogMessage(params: lsp.LogMessageParams): IO[Unit] = messages.update(params.message :: _)

  def windowShowMessage(params: lsp.ShowMessageParams): IO[Unit] = messages.update(params.message :: _)

  def initialized(params: lsp.InitializedParams): IO[Unit] = IO.unit

  // --- editor simulation ---

  def openFile(uri: SourceUri, text: String): IO[Unit] =
    for {
      _      <- buffers.update(_.updated(uri, TestClient.Buffer(text, version = 0)))
      server <- serverRef.get
      _      <- server.textDocumentDidOpen(
        lsp.DidOpenTextDocumentParams(
          lsp.TextDocumentItem(uri = uri.toLspUri, languageId = lsp.LanguageKind.SCALA, version = 0, text = text)
        )
      )
    } yield ()

  /** Replace `range` with `newText` — one incremental `didChange` with a single content change, the shape VS Code
    * emits for a typed edit or completion insertion.
    */
  def editFile(uri: SourceUri, range: lsp.Range, newText: String): IO[Unit] =
    for {
      version <- buffers.modify { m =>
        val buf  = m.getOrElse(uri, sys.error(s"editFile on unopened file $uri"))
        val next = buf.copy(text = TestClient.applyEdit(buf.text, range, newText), version = buf.version + 1)
        (m.updated(uri, next), next.version)
      }
      server <- serverRef.get
      _      <- server.textDocumentDidChange(
        lsp.DidChangeTextDocumentParams(
          lsp.VersionedTextDocumentIdentifier(uri = uri.toLspUri, version = version),
          contentChanges = List(
            lsp.TextDocumentContentChangeEvent
              .Case0Case(lsp.TextDocumentContentChangePartial(range = range, text = newText))
              .asInstanceOf[lsp.TextDocumentContentChangeEvent]
          ),
        )
      )
    } yield ()

  def insertAt(uri: SourceUri, pos: lsp.Position, text: String): IO[Unit] =
    editFile(uri, lsp.Range(pos, pos), text)

  /** Write the client buffer to disk, then notify — the order real editors use. */
  def saveFile(uri: SourceUri): IO[Unit] =
    for {
      content <- bufferOf(uri)
      _       <- IO.blocking(os.write.over(os.Path(uri.toPath.toNioPath), content))
      server  <- serverRef.get
      _       <- server.textDocumentDidSave(lsp.DidSaveTextDocumentParams(lsp.TextDocumentIdentifier(uri.toLspUri)))
    } yield ()

  def closeFile(uri: SourceUri): IO[Unit] =
    buffers.update(_ - uri) *>
      serverRef.get.flatMap(
        _.textDocumentDidClose(lsp.DidCloseTextDocumentParams(lsp.TextDocumentIdentifier(uri.toLspUri)))
      )

  def bufferOf(uri: SourceUri): IO[String] =
    buffers.get.map(_.getOrElse(uri, sys.error(s"no open buffer for $uri")).text)

  def latestDiagnostics(uri: SourceUri): IO[List[lsp.Diagnostic]] =
    diagnostics.get.map(_.getOrElse(uri.toLspUri, Nil))

  def logMessages: IO[List[String]] = messages.get.map(_.reverse)

  private[util] def completeServer(server: SlsLanguageServer[IO]): IO[Unit] =
    serverRef.complete(server).void
}

object TestClient {

  private[util] final case class Buffer(text: String, version: Int)

  def create: IO[TestClient] =
    for {
      serverRef <- Deferred[IO, SlsLanguageServer[IO]]
      buffers   <- Ref.of[IO, Map[SourceUri, Buffer]](Map.empty)
      diags     <- Ref.of[IO, Map[String, List[lsp.Diagnostic]]](Map.empty)
      messages  <- Ref.of[IO, List[String]](Nil)
    } yield new TestClient(serverRef, buffers, diags, messages)

  /** Poll `check` until it yields a value, failing after `timeout`. Async server work (e.g. the supervised
    * didSave → compile → index pipeline) has no completion notification yet, so tests await observable state.
    */
  def awaitUntil[A](
      check: IO[Option[A]],
      timeout: FiniteDuration = 30.seconds,
      interval: FiniteDuration = 50.millis,
      label: String = "condition",
  ): IO[A] = {
    def loop(deadline: FiniteDuration): IO[A] =
      check.flatMap {
        case Some(a) => IO.pure(a)
        case None    =>
          IO.monotonic.flatMap { now =>
            if now >= deadline then IO.raiseError(new RuntimeException(s"awaitUntil timed out waiting for $label"))
            else IO.sleep(interval) *> loop(deadline)
          }
      }
    IO.monotonic.flatMap(now => loop(now + timeout))
  }

  /** Apply an LSP range edit to a text buffer (0-indexed line/character offsets). */
  private[util] def applyEdit(content: String, range: lsp.Range, newText: String): String = {
    def offsetOf(line: Int, character: Int): Int = {
      var offset = 0
      var l      = 0
      while (l < line) {
        val nl = content.indexOf('\n', offset)
        require(nl >= 0, s"line $line out of bounds")
        offset = nl + 1
        l += 1
      }
      offset + character
    }
    val start = offsetOf(range.start.line, range.start.character)
    val end   = offsetOf(range.end.line, range.end.character)
    content.substring(0, start) + newText + content.substring(end)
  }
}
