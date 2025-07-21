package org.scala.abusers.sls.integration

import lsp.*

object ConcurrencyTests extends LSPIntegrationTestSuite {

  test("didOpen followed immediately by completion request") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _ <- initializeServer(ctx.server, ctx.workspace)
        _ <- ctx.server.initialized(InitializedParams())
        fileUri = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)

        // Send didOpen notification (fire-and-forget, starts internal processing)
        _ <- openDocument(ctx.server, fileUri, fileContent)

        // IMMEDIATELY send completion request right after didOpen notification
        // This simulates the race condition where a request arrives while didOpen setup is ongoing
        completionResult <- ctx.server
          .textDocumentCompletionOp(
            CompletionParams(
              textDocument = TextDocumentIdentifier(uri = fileUri),
              position = Position(line = 4, character = 10), // After "utils."
              context = Some(
                CompletionContext(
                  triggerKind = CompletionTriggerKind.TRIGGER_CHARACTER,
                  triggerCharacter = Some("."),
                )
              ),
            )
          )
          .attempt

      } yield
        // Request should not crash the server, even if it races with didOpen
        expect(completionResult.isRight)
    }
  }

  test("didOpen followed immediately by multiple different requests") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _ <- initializeServer(ctx.server, ctx.workspace)
        _ <- ctx.server.initialized(InitializedParams())
        fileUri = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)

        // Send didOpen notification
        _ <- openDocument(ctx.server, fileUri, fileContent)

        // IMMEDIATELY fire multiple different requests while didOpen is processing
        // This is the most likely scenario to reproduce race conditions
        completionResult <- ctx.server
          .textDocumentCompletionOp(
            CompletionParams(
              textDocument = TextDocumentIdentifier(uri = fileUri),
              position = Position(line = 1, character = 5),
              context = Some(CompletionContext(CompletionTriggerKind.INVOKED, None)),
            )
          )
          .attempt

        hoverResult <- ctx.server
          .textDocumentHoverOp(
            HoverParams(
              textDocument = TextDocumentIdentifier(uri = fileUri),
              position = Position(line = 2, character = 10),
            )
          )
          .attempt

        definitionResult <- ctx.server
          .textDocumentDefinitionOp(
            DefinitionParams(
              textDocument = TextDocumentIdentifier(uri = fileUri),
              position = Position(line = 3, character = 5),
            )
          )
          .attempt

      } yield
        // All requests should handle race conditions gracefully
        expect(completionResult.isRight) &&
          expect(hoverResult.isRight) &&
          expect(definitionResult.isRight)
    }
  }

  test("rapid sequential didOpen notifications") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _ <- initializeServer(ctx.server, ctx.workspace)
        _ <- ctx.server.initialized(InitializedParams())
        mainUri  = ctx.workspace.getSourceFileUri("Main.scala").get
        utilsUri = ctx.workspace.getSourceFileUri("Utils.scala").get
        mainContent  <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)
        utilsContent <- readFileContent(ctx.workspace.getSourceFile("Utils.scala").get)

        // Open documents rapidly in sequence to simulate race conditions
        // in state management between BSPStateManager and TextDocumentSyncManager
        _ <- openDocument(ctx.server, mainUri, mainContent)
        // Immediately open second document right after first one
        secondOpenResult <- openDocument(ctx.server, utilsUri, utilsContent).attempt

      } yield
        // Second document should open successfully without crashes, even if it races with first
        expect(secondOpenResult.isRight)
    }
  }

  test("didOpen followed immediately by didChange notification") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _ <- initializeServer(ctx.server, ctx.workspace)
        _ <- ctx.server.initialized(InitializedParams())
        fileUri = ctx.workspace.getSourceFileUri("Main.scala").get
        fileContent <- readFileContent(ctx.workspace.getSourceFile("Main.scala").get)

        // Send didOpen notification
        _ <- openDocument(ctx.server, fileUri, fileContent)

        // Immediately send change notification - this can race with didOpen processing
        changeResult <- ctx.server
          .textDocumentDidChange(
            DidChangeTextDocumentParams(
              textDocument = VersionedTextDocumentIdentifier(uri = fileUri, version = 2),
              contentChanges = List(
                TextDocumentContentChangeEvent
                  .Case1Case(
                    TextDocumentContentChangeWholeDocument(text = fileContent + "\\n  // Added comment\\n")
                  )
                  .asInstanceOf[TextDocumentContentChangeEvent]
              ),
            )
          )
          .attempt

      } yield
        // Change operation should not crash even if it races with didOpen
        expect(changeResult.isRight)
    }
  }
}
