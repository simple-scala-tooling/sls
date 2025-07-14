package org.scala.abusers.sls

import org.scala.abusers.sls.NioConverter.asNio
import weaver.SimpleIOSuite
import lsp.*

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

  loggedTest("applies full document change") { log =>
    val uri    = "/home/Test.scala"
    val client = TestClient(log)
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(client.input(open(uri, "Hello!")))

    _ <- mgr.didChange(
      client.input(
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
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2", found = doc.content)

  }

  loggedTest("applies incremental document change at the beggining") { log =>
    val uri    = "/home/Test.scala"
    val client = TestClient(log)
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(client.input(open(uri, "val z = 3")))

    _ <- mgr.didChange(
      client.input(
        DidChangeTextDocumentParams(
          VersionedTextDocumentIdentifier(version = 1, uri = uri),
          contentChanges = List(
            makeChange(startLine = 0, startChar = 0, endLine = 0, endChar = 0, text = "val x = 1\nval y = 2\n")
          ),
        )
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2\nval z = 3", found = doc.content)

  }

  loggedTest("applies incremental document change at the end") { log =>
    val uri    = "/home/Test.scala"
    val client = TestClient(log)
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(client.input(open(uri, "val x = 1\nval y = 2")))

    // full document replacement
    _ <- mgr.didChange(
      client.input(
        DidChangeTextDocumentParams(
          VersionedTextDocumentIdentifier(version = 1, uri = uri),
          contentChanges = List(makeChange(startLine = 1, startChar = 9, endLine = 1, endChar = 9, text = "\n")),
        )
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2\n", found = doc.content)

  }

  loggedTest("applies incremental document change with multi line change") { log =>
    val uri    = "/home/Test.scala"
    val client = TestClient(log)
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(client.input(open(uri, "val x = 1\nval y = 2\nval z = 3")))

    // full document replacement
    _ <- mgr.didChange(
      client.input(
        DidChangeTextDocumentParams(
          VersionedTextDocumentIdentifier(version = 1, uri = uri),
          contentChanges = List(
            makeChange(startLine = 1, startChar = 9, endLine = 1, endChar = 9, text = "\nval xx = 3\nval yy = 4\n")
          ),
        )
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\nval y = 2\nval xx = 3\nval yy = 4\n\nval z = 3", found = doc.content)
  }

  loggedTest("applies incremental document change with selection") { log =>
    val uri    = "/home/Test.scala"
    val client = TestClient(log)
    for mgr <- TextDocumentSyncManager.instance
    _       <- mgr.didOpen(client.input(open(uri, "val x = 1\nval y = 2\nval z = 3")))

    // full document replacement
    _ <- mgr.didChange(
      client.input(
        DidChangeTextDocumentParams(
          VersionedTextDocumentIdentifier(version = 1, uri = uri),
          contentChanges = List(makeChange(startLine = 1, startChar = 0, endLine = 1, endChar = 9, text = "p")),
        )
      )
    )

    doc <- mgr.get(java.net.URI(uri))
    yield expect.eql(expected = "val x = 1\np\nval z = 3", found = doc.content)

  }
}
