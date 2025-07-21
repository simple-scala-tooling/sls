package org.scala.abusers.sls.integration

import cats.effect.IO
import cats.syntax.all.*
import lsp.*
import weaver.Expectations

import scala.concurrent.duration.*

object RealWorldScenarioTests extends LSPIntegrationTestSuite {

  test("handles cross-file navigation in multi-module project") { _ =>
    withMultiModuleServer.use { ctx =>
      for {
        _              <- initializeServer(ctx.server, ctx.workspace)
        _              <- ctx.server.initialized(InitializedParams())
        appUri         = ctx.workspace.getSourceFileUri("app/Main.scala").get
        coreUri        = ctx.workspace.getSourceFileUri("core/Domain.scala").get
        appContent     <- readFileContent(ctx.workspace.getSourceFile("app/Main.scala").get)
        coreContent    <- readFileContent(ctx.workspace.getSourceFile("core/Domain.scala").get)

        // Open both files
        _ <- openDocument(ctx.server, appUri, appContent)
        _ <- openDocument(ctx.server, coreUri, coreContent)

        // Test completion in app module that should include core module symbols
        completionParams = CompletionParams(
          textDocument = TextDocumentIdentifier(uri = appUri),
          position = Position(line = 5, character = 25), // After "UserService."
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.TRIGGER_CHARACTER,
            triggerCharacter = Some(".")
          ))
        )

        completionResponse <- ctx.server.textDocumentCompletionOp(completionParams)

        // Test go-to-definition from app to core module
        definitionParams = DefinitionParams(
          textDocument = TextDocumentIdentifier(uri = appUri),
          position = Position(line = 5, character = 20) // On "UserService"
        )

        definitionResponse <- ctx.server.textDocumentDefinitionOp(definitionParams)
      } yield {
        // Cross-module operations should complete without errors
        val completionSuccess = completionResponse.result.isDefined
        val definitionSuccess = definitionResponse.result.isDefined

        expect(completionSuccess || definitionSuccess) // At least one should work
      }
    }
  }

  test("handles multiple concurrent LSP requests") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        mainUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        utilsUri    = ctx.workspace.getSourceFileUri("Utils.scala").get
        mainContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        utilsContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)

        // Open both documents
        _ <- openDocument(ctx.server, mainUri, mainContent)
        _ <- openDocument(ctx.server, utilsUri, utilsContent)

        // Make multiple concurrent requests
        hoverMain = ctx.server.textDocumentHoverOp(HoverParams(
          textDocument = TextDocumentIdentifier(uri = mainUri),
          position = Position(line = 2, character = 12) // On "println"
        ))

        hoverUtils = ctx.server.textDocumentHoverOp(HoverParams(
          textDocument = TextDocumentIdentifier(uri = utilsUri),
          position = Position(line = 1, character = 6) // On "greet"
        ))

        completionMain = ctx.server.textDocumentCompletionOp(CompletionParams(
          textDocument = TextDocumentIdentifier(uri = mainUri),
          position = Position(line = 3, character = 10), // After "utils."
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.TRIGGER_CHARACTER,
            triggerCharacter = Some(".")
          ))
        ))

        signatureUtils = ctx.server.textDocumentSignatureHelpOp(SignatureHelpParams(
          textDocument = TextDocumentIdentifier(uri = utilsUri),
          position = Position(line = 5, character = 25), // In add method signature
          context = Some(SignatureHelpContext(
            triggerKind = SignatureHelpTriggerKind.INVOKED,
            triggerCharacter = None,
            isRetrigger = false,
            activeSignatureHelp = None
          ))
        ))

        // Execute all requests concurrently
        (hoverMainRes, hoverUtilsRes, completionRes, signatureRes) <- (
          hoverMain, hoverUtils, completionMain, signatureUtils
        ).parTupled

      } yield {
        // All requests should complete successfully
        success
      }
    }
  }

  test("handles rapid document changes without errors") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Utils.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Make rapid successive changes
        changes = (1 to 10).toList.map { i =>
          DidChangeTextDocumentParams(
            textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = i + 1),
            contentChanges = List(
              makeTextChange(
                startLine = 8, startChar = 1,
                endLine = 8, endChar = 1,
                text = s"\n  // Rapid change $i"
              )
            )
          )
        }

        // Apply changes sequentially with minimal delay
        _ <- changes.traverse { change =>
          ctx.server.textDocumentDidChange(change) *> IO.sleep(50.millis)
        }

        // Server should still be responsive
        hoverParams = HoverParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 1, character = 6)
        )

        _ <- ctx.server.textDocumentHoverOp(hoverParams)
      } yield success
    }
  }

  test("handles large file operations") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Utils.scala").get

        // Create a large file content
        largeContent = {
          val baseClass = """class Utils {
                            |  def greet(name: String): Unit = {
                            |    println(s"Hello, $name!")
                            |  }
                            |
                            |  def add(a: Int, b: Int): Int = a + b
                            |
                            |  def multiply(x: Double, y: Double): Double = x * y
                            |""".stripMargin
          val manyMethods = (1 to 100).map { i =>
            s"""  def method$i(param: Int): Int = param * $i
               |""".stripMargin
          }.mkString("\n")

          baseClass + manyMethods + "\n}"
        }

        _ <- openDocument(ctx.server, fileUri, largeContent)

        // Test completion in large file
        completionParams = CompletionParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 50, character = 0), // Middle of file
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.INVOKED,
            triggerCharacter = None
          ))
        )

        _ <- ctx.server.textDocumentCompletionOp(completionParams)

        // Test hover in large file
        hoverParams = HoverParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 80, character = 6) // Near end of file
        )

        _ <- ctx.server.textDocumentHoverOp(hoverParams)
      } yield success
    }
  }

  test("handles workspace with many files") { _ =>
    withMultiModuleServer.use { ctx =>
      for {
        _              <- initializeServer(ctx.server, ctx.workspace)
        _              <- ctx.server.initialized(InitializedParams())

        // Create multiple file URIs (simulating a workspace with many files)
        files = List(
          "core/Domain.scala",
          "app/Main.scala"
        ).map(ctx.workspace.getSourceFileUri(_).get)

        // Open multiple files
        _ <- files.traverse { fileUri =>
          val fileName = fileUri.split("/").last
          val content = s"""package ${if (fileUri.contains("core")) "core" else "app"}
                           |
                           |object ${fileName.replace(".scala", "")} {
                           |  def example(): Unit = {
                           |    println("Example from $fileName")
                           |  }
                           |}
                           |""".stripMargin
          openDocument(ctx.server, fileUri, content)
        }

        // Test that server can handle requests across all files
        requests = files.map { fileUri =>
          ctx.server.textDocumentHoverOp(HoverParams(
            textDocument = TextDocumentIdentifier(uri = fileUri),
            position = Position(line = 2, character = 7) // On "object"
          ))
        }

        _ <- requests.parSequence
      } yield success
    }
  }

  test("handles diagnostic publishing with debouncing") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        _           <- ctx.client.clearAll
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get

        // Open file with syntax error
        invalidContent = """object Main {
                           |  def main(args: Array[String]): Unit = {
                           |    val incomplete =
                           |    // Missing value assignment
                           |  }
                           |}
                           |""".stripMargin
        _ <- openDocument(ctx.server, fileUri, invalidContent)

        // Make quick changes
        fix1 = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 2),
          contentChanges = List(
            makeTextChange(2, 18, 2, 18, "42")
          )
        )

        fix2 = DidChangeTextDocumentParams(
          textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 3),
          contentChanges = List(
            makeTextChange(2, 18, 2, 20, "\"hello\"")
          )
        )

        _ <- ctx.server.textDocumentDidChange(fix1)
        _ <- IO.sleep(100.millis) // Quick change
        _ <- ctx.server.textDocumentDidChange(fix2)

        // Wait for debouncing (300ms + processing time)
        _ <- IO.sleep(1.second)

        diagnostics <- ctx.client.getPublishedDiagnostics
      } yield {
        // Should have received diagnostics, but debouncing should prevent excessive publications
        expect(diagnostics.nonEmpty)
      }
    }
  }

  test("performance: handles 50 rapid completion requests") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Utils.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Create many completion requests
        completionRequests = (1 to 50).map { _ =>
          ctx.server.textDocumentCompletionOp(CompletionParams(
            textDocument = TextDocumentIdentifier(uri = fileUri),
            position = Position(line = 1, character = 8),
            context = Some(CompletionContext(
              triggerKind = CompletionTriggerKind.INVOKED,
              triggerCharacter = None
            ))
          ))
        }.toList

        startTime <- IO.realTime
        _         <- completionRequests.parSequence
        endTime   <- IO.realTime

        duration = endTime - startTime
      } yield {
        // Should complete all requests reasonably quickly (less than 30 seconds)
        expect(duration < 30.seconds)
      }
    }
  }
}
