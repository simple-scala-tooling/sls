package org.scala.abusers.sls

import cats.effect.*
import cats.syntax.all.*
import fs2.io.process.Process
import jsonrpclib.fs2.{lsp => jsonrpclibLsp, *}
import jsonrpclib.smithy4sinterop.ClientStub
import org.scala.abusers.csp
import org.scala.abusers.csp.CspClient
import org.scala.abusers.csp.CspServer

import java.nio.file.Paths

/** Receives zinc-cli notifications: compile diagnostics are forwarded to the editor via [[DiagnosticManager]]. An
  * empty diagnostics list clears the file (the CSP server tracks which files it reported for).
  */
class ZincCspClient(lspClient: SlsLanguageClient[IO], diagnosticManager: DiagnosticManager) extends CspClient[IO] {

  def onPublishDiagnostics(params: csp.PublishDiagnosticsParams): IO[Unit] = {
    val uri   = Paths.get(params.file).toSourceUri
    val diags = params.diagnostics.map(toLsp)
    diagnosticManager.onCspPublishDiagnostics(lspClient, uri, diags)
  }

  private def toLsp(d: csp.CompileDiagnostic): lsp.Diagnostic = {
    val range = d.range
      .map(r => lsp.Range(lsp.Position(r.startLine, r.startCharacter), lsp.Position(r.endLine, r.endCharacter)))
      .getOrElse(lsp.Range(lsp.Position(0, 0), lsp.Position(0, 0)))
    val severity = d.severity match {
      case csp.DiagnosticSeverity.ERROR   => lsp.DiagnosticSeverity.ERROR
      case csp.DiagnosticSeverity.WARNING => lsp.DiagnosticSeverity.WARNING
      case csp.DiagnosticSeverity.INFO    => lsp.DiagnosticSeverity.INFORMATION
    }
    lsp.Diagnostic(range = range, message = d.message, severity = severity.some, source = "sls-compile".some)
  }
}

object ZincCspClient {
  private val cspStderrLogger = org.slf4j.LoggerFactory.getLogger("org.scala.abusers.sls.CspStderr")

  def makeCspClient(
      process: Process[IO],
      channel: FS2Channel[IO],
      report: String => IO[Unit],
  ): Resource[IO, CspServer[IO]] =
    fs2.Stream
      .eval(IO.never)
      .concurrently(
        channel.output
          .through(CspMessageTracer.traceOutgoing)
          .through(jsonrpclibLsp.encodeMessages)
          .through(process.stdin)
      )
      .concurrently(
        process.stdout
          .through(jsonrpclibLsp.decodeMessages)
          .through(CspMessageTracer.traceIncoming)
          .through(channel.inputOrBounce)
      )
      .concurrently(
        process.stderr
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .evalMap(line => IO(cspStderrLogger.warn(line)))
      )
      .compile
      .drain
      .handleErrorWith { e =>
        IO(cspStderrLogger.error("CSP server stream failed", e))
      }
      .guarantee(IO(cspStderrLogger.warn("Terminating csp server")))
      .background
      .as(
        ClientStub(CspServer, channel).toOption.getOrElse(
          sys.error("Couldn't create ClientStub")
        )
      )
}
