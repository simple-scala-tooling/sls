package org.scala.abusers.sls // TODO also we should get whole package completion out of the box

import cats.effect.*
import cats.effect.std.AtomicCell
import cats.parse.LocationMap
import cats.syntax.all.*

case class DocumentState(content: String, uri: SourceUri) {
  private lazy val locationMap = LocationMap(content)

  extension (lspPos: lsp.Position) {
    def toOffset: Int =
      locationMap.toOffset(lspPos.line, lspPos.character).getOrElse(-1 /* no such line */ )
  }

  def processEdits(edits: List[lsp.TextDocumentContentChangeEvent]): DocumentState =
    edits.toList
      .foldLeft(this) {
        case (currentState, lsp.TextDocumentContentChangeEvent.Case0Case(incremental)) => currentState.applyEdit(incremental)
        case (_, lsp.TextDocumentContentChangeEvent.Case1Case(full))        => DocumentState(full.text, uri)
        case _                                                              => sys.error("Illegal State Exception")
      }

  private def applyEdit(edit: lsp.TextDocumentContentChangePartial): DocumentState = {
    val lsp.Range(startPos, endPos) = edit.range
    val startOffset                 = startPos.toOffset
    val endOffset                   = endPos.toOffset
    val init                        = content.take(startOffset)
    val end                         = content.drop(endOffset)
    DocumentState(init ++ edit.text ++ end, uri)
  }
}

object TextDocumentSyncManager {
  def instance: IO[TextDocumentSyncManager] =
    AtomicCell[IO].of(Map[SourceUri, DocumentState]()).map(TextDocumentSyncManager(_))
}

class TextDocumentSyncManager(val documents: AtomicCell[IO, Map[SourceUri, DocumentState]]) {

  def didOpen(params: lsp.DidOpenTextDocumentParams)(using SynchronizedState): IO[Unit] =
    getOrCreateDocument(params.textDocument.sourceUri, params.textDocument.text.some).void

  def didChange(params: lsp.DidChangeTextDocumentParams)(using SynchronizedState) =
    onTextEditReceived(params.textDocument.sourceUri, params.contentChanges)

  def didClose(params: lsp.DidCloseTextDocumentParams)(using SynchronizedState): IO[Unit] =
    documents.update(_.removed(params.textDocument.sourceUri))

  def didSave(params: lsp.DidSaveTextDocumentParams)(using SynchronizedState): IO[Unit] =
    getOrCreateDocument(params.textDocument.sourceUri, params.text).void

  private def onTextEditReceived(uri: SourceUri, edits: List[lsp.TextDocumentContentChangeEvent]): IO[Unit] =
    for {
      doc <- getOrCreateDocument(uri, None)
      _   <- documents.update(_.updated(uri, doc.processEdits(edits)))
    } yield ()

  def get(uri: SourceUri)(using SynchronizedState): IO[DocumentState] =
    documents.get.map(_.get(uri)).flatMap(IO.fromOption(_)(IllegalStateException()))

  private def getOrCreateDocument(uri: SourceUri, content: Option[String]): IO[DocumentState] =
    documents.modify { access =>
      access.get(uri) match {
        case Some(doc) => access -> doc
        case None =>
          val newDoc = new DocumentState(content.getOrElse(""), uri)
          access.updated(uri, newDoc) -> newDoc
      }
    }
}
