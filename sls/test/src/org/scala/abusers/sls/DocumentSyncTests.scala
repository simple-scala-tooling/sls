package org.scala.abusers.sls

import lsp.*
import weaver.SimpleIOSuite

object TextDocumentSyncSuite extends SimpleIOSuite {

  def makeChange(
      startLine: Int,
      startChar: Int,
      endLine: Int,
      endChar: Int,
      text: String,
  ): TextDocumentContentChangeEvent =
    TextDocumentContentChangeEvent
      .Case0Case(
        TextDocumentContentChangePartial(
          range = Range(
            start = Position(startLine, startChar),
            end = Position(endLine, endChar),
          ),
          text = text,
        )
      )
      .asInstanceOf[TextDocumentContentChangeEvent]

  def open(uri: String, text: String): DidOpenTextDocumentParams =
    DidOpenTextDocumentParams(
      TextDocumentItem(uri = uri, languageId = LanguageKind.SCALA, version = 0, text = text)
    )

  test("applies full document change") { _ =>
    val uri = "/home/Test.scala"
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(open(uri, "Hello!"))

    _ <- mgr.didChange(
      DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(version = 1, uri = uri),
        contentChanges = List(
          TextDocumentContentChangeEvent
            .Case1Case(
              TextDocumentContentChangeWholeDocument(
                text = "val x = 1\nval y = 2"
              )
            )
            .asInstanceOf[TextDocumentContentChangeEvent]
        ),
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2", found = doc.content)

  }

  test("applies incremental document change at the beggining") { _ =>
    val uri = "/home/Test.scala"
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(open(uri, "val z = 3"))

    _ <- mgr.didChange(
      DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(version = 1, uri = uri),
        contentChanges = List(
          makeChange(startLine = 0, startChar = 0, endLine = 0, endChar = 0, text = "val x = 1\nval y = 2\n")
        ),
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2\nval z = 3", found = doc.content)

  }

  test("applies incremental document change at the end") { _ =>
    val uri = "/home/Test.scala"
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(open(uri, "val x = 1\nval y = 2"))

    // full document replacement
    _ <- mgr.didChange(
      DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(version = 1, uri = uri),
        contentChanges = List(makeChange(startLine = 1, startChar = 9, endLine = 1, endChar = 9, text = "\n")),
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2\n", found = doc.content)

  }

  test("applies incremental document change with multi line change") { _ =>
    val uri = "/home/Test.scala"
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(open(uri, "val x = 1\nval y = 2\nval z = 3"))

    // full document replacement
    _ <- mgr.didChange(
      DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(version = 1, uri = uri),
        contentChanges = List(
          makeChange(startLine = 1, startChar = 9, endLine = 1, endChar = 9, text = "\nval xx = 3\nval yy = 4\n")
        ),
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2\nval xx = 3\nval yy = 4\n\nval z = 3", found = doc.content)
  }

  test("applies incremental document change with selection") { _ =>
    val uri = "/home/Test.scala"
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(open(uri, "val x = 1\nval y = 2\nval z = 3"))

    // full document replacement
    _ <- mgr.didChange(
      DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(version = 1, uri = uri),
        contentChanges = List(makeChange(startLine = 1, startChar = 0, endLine = 1, endChar = 9, text = "p")),
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\np\nval z = 3", found = doc.content)

  }
}
