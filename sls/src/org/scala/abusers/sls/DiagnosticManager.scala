package org.scala.abusers.sls

import bsp.Diagnostic as BspDiagnostic
import bsp.PublishDiagnosticsParams as BspPublishDiagnosticsParams
import cats.effect.std.MapRef
import cats.effect.IO
import cats.syntax.all.*
import org.scala.abusers.sls.NioConverter.*
import smithy4s.json.Json

import java.net.URI

/** Diagnostic State Manager
  *
  * This class is reposnobile for holding the state of the diagnostic displayed on the client
  *
  * Proposed heuristic is: On file save we trigger compilation, and this results in notifications being sent from BSP
  * server. We will keep adding diagnostics, and will clean them only when [[PublishDiagnosticsParams.reset]] is
  * set to true.
  *
  * @param publishedDiagnostics
  */
 // FIXME revert this to URI
class DiagnosticManager(publishedDiagnostics: MapRef[IO, String, Option[Set[lsp.Diagnostic]]]) {
  private def convertDiagnostic(bspDiag: BspDiagnostic): lsp.Diagnostic = {
    val data = Json.writeBlob(bspDiag)
    // to be chimneyed
    Json.read[lsp.Diagnostic](data).toOption.getOrElse(sys.error(s"Failed to convert BSP diagnostic to LSP: $data"))
  }

  def didChange(client: SlsLanguageClient[IO], uri: String, pcDiags: List[lsp.Diagnostic]): IO[Unit] =
    // remove diagnostic on modified lines
    // ask presentation compiler for diagnostics
    for {
      _ <- publishedDiagnostics(uri).set(pcDiags.toSet.some)
      request = lsp.PublishDiagnosticsParams(uri, pcDiags, None)
      _ <- client.textDocumentPublishDiagnostics(request)
    } yield ()

  def onBuildPublishDiagnostics(client: SlsLanguageClient[IO], input: BspPublishDiagnosticsParams): IO[Unit] = {
    val bspUri   = input.textDocument.uri
    val lspDiags = input.diagnostics.toSet.map(convertDiagnostic)
    def request(diags: Set[lsp.Diagnostic]) =
      lsp.PublishDiagnosticsParams(bspUri.value, diags.toList, None)

    if input.reset then {
      for {
        _ <- publishedDiagnostics(input.textDocument.uri.value).set(lspDiags.some)
        _ <- client.textDocumentPublishDiagnostics(lsp.PublishDiagnosticsParams(input.textDocument.uri.value, lspDiags.toList))
      } yield ()

    } else {
      for {
        currentDiags <- publishedDiagnostics(bspUri.value).updateAndGet(_.foldLeft(lspDiags)(_ ++ _).some)
        _            <- client.textDocumentPublishDiagnostics(request(currentDiags.get))
      } yield ()
    }
  }
}

object DiagnosticManager {
  def instance: IO[DiagnosticManager] =
    MapRef.ofScalaConcurrentTrieMap[IO, String, Set[lsp.Diagnostic]].map(DiagnosticManager.apply)
}
