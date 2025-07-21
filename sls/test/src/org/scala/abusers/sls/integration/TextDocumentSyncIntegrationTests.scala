package org.scala.abusers.sls.integration

import cats.effect.IO
import cats.syntax.all.*
import lsp.*
import weaver.Expectations

import scala.concurrent.duration.*

object TextDocumentSyncIntegrationTests extends LSPIntegrationTestSuite {

  test("opens and tracks Scala document") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)
        // Document should be tracked internally - this tests the flow doesn't error
      } yield success
    }
  }

  test("handles incremental document changes") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Make an incremental change - add a new line at the end
        changeParams = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 2),
          contentChanges = List(
            makeTextChange(
              startLine = 5, startChar = 1, // After the closing brace
              endLine = 5, endChar = 1,
              text = "\n\n// Integration test comment"
            )
          )
        )
        _ <- ctx.server.textDocumentDidChange(changeParams)
      } yield success
    }
  }

  test("handles full document replacement") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Utils.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Full document replacement
        newContent = """class Utils {
                        |  def greet(name: String): Unit = {
                        |    println(s"Greetings, $name!")
                        |  }
                        |
                        |  def subtract(a: Int, b: Int): Int = a - b
                        |}
                        |""".stripMargin
        changeParams = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 2),
          contentChanges = List(
            TextDocumentContentChangeEvent.Case1Case(
              TextDocumentContentChangeWholeDocument(text = newContent)
            ).asInstanceOf[TextDocumentContentChangeEvent]
          )
        )
        _ <- ctx.server.textDocumentDidChange(changeParams)
      } yield success
    }
  }

  test("handles document save notification") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Save the document
        saveParams = DidSaveTextDocumentParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          text = Some(fileContent)
        )
        _ <- ctx.server.textDocumentDidSave(saveParams)
      } yield success
    }
  }

  test("handles document close notification") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Close the document
        closeParams = DidCloseTextDocumentParams(
          textDocument = TextDocumentIdentifier(uri = fileUri)
        )
        _ <- ctx.server.textDocumentDidClose(closeParams)
      } yield success
    }
  }

  /* Will not work without diagnostic changes to the compiler */
  test("publishes diagnostics after document changes".ignore) { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        _           <- ctx.client.clearAll // Clear any initial diagnostics
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get

        // Open a document with syntax error
        invalidContent = """object Main {
                           |  def main(args: Array[String]): Unit = {
                           |    println("Hello, World!"
                           |    // Missing closing parenthesis - syntax error
                           |  }
                           |}
                           |""".stripMargin
        _ <- openDocument(ctx.server, fileUri, invalidContent)

        // Wait a bit for debounced diagnostics
        _ <- IO.sleep(1000.millis)

        // Check if diagnostics were published
        diagnostics <- ctx.client.getPublishedDiagnostics
      } yield {
        // We expect at least one diagnostic publication
        expect(diagnostics.nonEmpty)
      }
    }
  }

  test("handles multiple document operations in sequence") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _            <- initializeServer(ctx.server, ctx.workspace)
        _            <- ctx.server.initialized(InitializedParams())
        mainUri      = ctx.workspace.getSourceFileUri("Main.scala").get
        utilsUri     = ctx.workspace.getSourceFileUri("Utils.scala").get
        mainContent  <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        utilsContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)

        // Open both documents
        _ <- openDocument(ctx.server, mainUri, mainContent)
        _ <- openDocument(ctx.server, utilsUri, utilsContent)

        // Modify Main.scala
        mainChangeParams = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = mainUri, version = 2),
          contentChanges = List(
            makeTextChange(
              startLine = 2, startChar = 4,
              endLine = 2, endChar = 4,
              text = "\n    val testVar = 42"
            )
          )
        )
        _ <- ctx.server.textDocumentDidChange(mainChangeParams)

        // Modify Utils.scala
        utilsChangeParams = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = utilsUri, version = 2),
          contentChanges = List(
            makeTextChange(
              startLine = 8, startChar = 1,
              endLine = 8, endChar = 1,
              text = "\n  \n  def divide(a: Double, b: Double): Double = a / b"
            )
          )
        )
        _ <- ctx.server.textDocumentDidChange(utilsChangeParams)

        // Save both documents
        _ <- ctx.server.textDocumentDidSave(DidSaveTextDocumentParams(
          textDocument = TextDocumentIdentifier(uri = mainUri),
          text = None
        ))
        _ <- ctx.server.textDocumentDidSave(DidSaveTextDocumentParams(
          textDocument = TextDocumentIdentifier(uri = utilsUri),
          text = None
        ))

        // Close Utils.scala
        _ <- ctx.server.textDocumentDidClose(DidCloseTextDocumentParams(
          textDocument = TextDocumentIdentifier(uri = utilsUri)
        ))
      } yield success
    }
  }

  test("handles document changes with version tracking") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Make multiple versioned changes
        change1 = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 2),
          contentChanges = List(
            makeTextChange(0, 0, 0, 0, "// Version 2\n")
          )
        )
        _ <- ctx.server.textDocumentDidChange(change1)

        change2 = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 3),
          contentChanges = List(
            makeTextChange(1, 0, 1, 0, "// Version 3\n")
          )
        )
        _ <- ctx.server.textDocumentDidChange(change2)

        change3 = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 4),
          contentChanges = List(
            makeTextChange(2, 0, 2, 0, "// Version 4\n")
          )
        )
        _ <- ctx.server.textDocumentDidChange(change3)
      } yield success
    }
  }
}
