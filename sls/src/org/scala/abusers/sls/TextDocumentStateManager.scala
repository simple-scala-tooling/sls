package org.scala.abusers.sls // TODO also we should get whole package completion out of the box

import cats.effect.*
import cats.effect.std.AtomicCell
import cats.parse.LocationMap
import cats.syntax.all.*

import java.net.URI
import lsp.TextDocumentContentChangePartial
import lsp.TextDocumentContentChangeWholeDocument

case class DocumentState(content: String, uri: URI) {
  private lazy val locationMap = LocationMap(content)

  extension (lspPos: lsp.Position) {
    def toOffset: Int =
      locationMap.toOffset(lspPos.line, lspPos.character).getOrElse(-1 /* no such line */ )
  }

  def processEdits(edits: List[lsp.TextDocumentContentChangeEvent]): DocumentState =
    edits.toList
      .foldLeft(this) {
        case (_, lsp.TextDocumentContentChangeEvent.Case0Case(incremental)) => applyEdit(incremental)
        case (_, lsp.TextDocumentContentChangeEvent.Case1Case(full))        => DocumentState(full.text, uri)
        case _                                                   => sys.error("Illegal State Exception")
      }

  private def applyEdit(edit: lsp.TextDocumentContentChangePartial): DocumentState = {
    val lsp.Range(startPos, endPos) = edit.range
    val startOffset             = startPos.toOffset
    val endOffset               = endPos.toOffset
    val init                    = content.take(startOffset)
    val end                     = content.drop(endOffset)
    DocumentState(init ++ edit.text ++ end, uri)
  }
}

object TextDocumentSyncManager {
  def instance: IO[TextDocumentSyncManager] =
    AtomicCell[IO].of(Map[URI, DocumentState]()).map(TextDocumentSyncManager(_))
}

class TextDocumentSyncManager(val documents: AtomicCell[IO, Map[URI, DocumentState]]) {
  import NioConverter.*

  def didOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit] =
    getOrCreateDocument(URI.create(params.textDocument.uri), params.textDocument.text.some).void

  def didChange(params: lsp.DidChangeTextDocumentParams) =
    onTextEditReceived(URI(params.textDocument.uri), params.contentChanges)

  def didClose(params: lsp.DidCloseTextDocumentParams): IO[Unit] =
    documents.update(_.removed(URI(params.textDocument.uri)))

  def didSave(params: lsp.DidSaveTextDocumentParams): IO[Unit] =
    getOrCreateDocument(URI(params.textDocument.uri), params.text).void

  private def onTextEditReceived(uri: URI, edits: List[lsp.TextDocumentContentChangeEvent]): IO[Unit] =
    for {
      doc <- getOrCreateDocument(uri, None)
      _   <- documents.update(_.updated(uri, doc.processEdits(edits)))
    } yield ()

  def get(uri: URI): IO[DocumentState] =
    documents.get.map(_.get(uri)).flatMap(IO.fromOption(_)(IllegalStateException()))

  private def getOrCreateDocument(uri: URI, content: Option[String]): IO[DocumentState] =
    documents.modify { access =>
      access.get(uri) match {
        case Some(doc) => access -> doc
        case None =>
          val newDoc = new DocumentState(content.getOrElse(""), uri)
          access.updated(uri, newDoc) -> newDoc
      }
    }
}
