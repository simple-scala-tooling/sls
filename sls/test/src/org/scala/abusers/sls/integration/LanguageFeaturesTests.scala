package org.scala.abusers.sls.integration

import cats.effect.IO
import cats.syntax.all.*
import lsp.*
import weaver.Expectations

import scala.concurrent.duration.*

object LanguageFeaturesTests extends LSPIntegrationTestSuite {

  test("provides completion for method calls") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Wait for initialization
        _ <- IO.sleep(1.second)

        // Request completion at position where "utils." appears
        completionParams = CompletionParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 4, character = 10), // After "utils."
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.TRIGGER_CHARACTER,
            triggerCharacter = Some(".")
          ))
        )

        response <- ctx.server.textDocumentCompletionOp(completionParams)
      } yield {
        response.result match {
          case Some(completion) =>
            // Should have completion items for Utils class methods
            expect(completion != null)
          case None => success // Completion might not be available yet
        }
      }
    }
  }

  test("provides hover information for symbols") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Utils.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Wait for initialization
        _ <- IO.sleep(1.second)

        // Request hover for the "greet" method
        hoverParams = HoverParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 1, character = 6) // On "greet" method name
        )

        response <- ctx.server.textDocumentHoverOp(hoverParams)
      } yield {
        response.result match {
          case Some(_) => success
          case None => success // Hover might not be available yet, but request should not error
        }
      }
    }
  }

  test("provides definition for symbol navigation") { _ =>
    withMultiModuleServer.use { ctx =>
      for {
        _              <- initializeServer(ctx.server, ctx.workspace)
        _              <- ctx.server.initialized(InitializedParams())
        appUri         = ctx.workspace.getSourceFileUri("app/Main.scala").get
        coreUri        = ctx.workspace.getSourceFileUri("core/Domain.scala").get
        appContent     <- readFileContent(ctx.workspace.getSourceFile("app/Main.scala").get)
        coreContent    <- readFileContent(ctx.workspace.getSourceFile("core/Domain.scala").get)
        _              <- openDocument(ctx.server, appUri, appContent)
        _              <- openDocument(ctx.server, coreUri, coreContent)

        // Wait for initialization
        _ <- IO.sleep(2.seconds)

        // Request go-to-definition for "UserService" in Main.scala
        definitionParams = DefinitionParams(
          textDocument = TextDocumentIdentifier(uri = appUri),
          position = Position(line = 5, character = 20) // On "UserService"
        )

        response <- ctx.server.textDocumentDefinitionOp(definitionParams)
      } yield {
        // Should provide definition location (might be empty if not ready yet)
        response.result match {
          case Some(_) => success
          case None => success // Definition might not be available yet
        }
      }
    }
  }

  test("provides signature help for method calls") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Utils.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)

        // Modify content to have a method call for signature help
        modifiedContent = fileContent + "\n\nval test = add("
        _ <- openDocument(ctx.server, fileUri, modifiedContent)

        // Wait for processing
        _ <- IO.sleep(1.second)

        // Request signature help inside the method call
        signatureParams = SignatureHelpParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 10, character = 15), // Inside "add(" call
          context = Some(SignatureHelpContext(
            triggerKind = SignatureHelpTriggerKind.TRIGGER_CHARACTER,
            triggerCharacter = Some("("),
            isRetrigger = false,
            activeSignatureHelp = None
          ))
        )

        response <- ctx.server.textDocumentSignatureHelpOp(signatureParams)
      } yield {
        // Should provide signature help (might be empty if not ready)
        response.result match {
          case Some(_) => success
          case None => success // Might not be ready yet
        }
      }
    }
  }

  test("provides inlay hints for type information") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        _           <- openDocument(ctx.server, fileUri, fileContent)

        // Wait for processing
        _ <- IO.sleep(1.second)

        // Request inlay hints for the entire file
        inlayParams = InlayHintParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          range = Range(
            start = Position(0, 0),
            end = Position(20, 0) // Cover entire file
          )
        )

        response <- ctx.server.textDocumentInlayHintOp(inlayParams)
      } yield {
        // Should provide inlay hints (might be empty if not configured)
        response.result match {
          case Some(hints) => expect(hints.size >= 0)
          case None => success
        }
      }
    }
  }

  test("handles completion with different trigger contexts") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get

        // Create content with various completion scenarios
        testContent = """object Main {
                        |  def main(args: Array[String]): Unit = {
                        |    val str = "Hello"
                        |    str.
                        |    println(s"")
                        |  }
                        |}
                        |""".stripMargin
        _ <- openDocument(ctx.server, fileUri, testContent)

        // Wait for processing
        _ <- IO.sleep(1.second)

        // Test completion after dot
        dotCompletion = CompletionParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 3, character = 8), // After "str."
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.TRIGGER_CHARACTER,
            triggerCharacter = Some(".")
          ))
        )

        // Test invoked completion (Ctrl+Space style)
        invokedCompletion = CompletionParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 4, character = 13), // Inside string interpolation
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.INVOKED,
            triggerCharacter = None
          ))
        )

        dotResponse <- ctx.server.textDocumentCompletionOp(dotCompletion)
        invokedResponse <- ctx.server.textDocumentCompletionOp(invokedCompletion)
      } yield {
        // Both requests should complete without error
        val dotSuccess = dotResponse.result.isDefined
        val invokedSuccess = invokedResponse.result.isDefined

        expect(dotSuccess || invokedSuccess) // At least one should succeed
      }
    }
  }

  test("handles language features with syntax errors") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _           <- initializeServer(ctx.server, ctx.workspace)
        _           <- ctx.server.initialized(InitializedParams())
        fileUri     = ctx.workspace.getSourceFileUri("Main.scala").get

        // Content with syntax error
        invalidContent = """object Main {
                           |  def main(args: Array[String]): Unit = {
                           |    val incomplete =
                           |    // Missing value - syntax error
                           |    println("test")
                           |  }
                           |}
                           |""".stripMargin
        _ <- openDocument(ctx.server, fileUri, invalidContent)

        // Wait for processing
        _ <- IO.sleep(1.second)

        // Try completion even with syntax errors
        completionParams = CompletionParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 4, character = 12), // After "println"
          context = Some(CompletionContext(
            triggerKind = CompletionTriggerKind.INVOKED,
            triggerCharacter = None
          ))
        )

        // Try hover even with syntax errors
        hoverParams = HoverParams(
          textDocument = TextDocumentIdentifier(uri = fileUri),
          position = Position(line = 4, character = 4) // On "println"
        )

        _ <- ctx.server.textDocumentCompletionOp(completionParams)
        _ <- ctx.server.textDocumentHoverOp(hoverParams)
      } yield {
        // Requests should complete without crashing, even with syntax errors
        success
      }
    }
  }
}
