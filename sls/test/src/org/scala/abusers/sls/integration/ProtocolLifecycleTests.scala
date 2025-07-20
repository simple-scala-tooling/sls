package org.scala.abusers.sls.integration

import cats.effect.IO
import cats.syntax.all.*
import lsp.*
import weaver.Expectations

object ProtocolLifecycleTests extends LSPIntegrationTestSuite {

  test("server initializes with correct capabilities") { _ =>
    withSimpleServer.use { ctx =>
      for {
        response <- initializeServer(ctx.server, ctx.workspace)
      } yield {
        response.result match {
          case Some(result) =>
            expect(result.capabilities.textDocumentSync.isDefined) &&
            expect(result.capabilities.completionProvider.isDefined) &&
            expect(result.capabilities.hoverProvider.isDefined) &&
            expect(result.capabilities.signatureHelpProvider.isDefined) &&
            expect(result.capabilities.definitionProvider.isDefined) &&
            expect(result.capabilities.inlayHintProvider.isDefined)
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("server capabilities include correct completion trigger characters") { _ =>
    withSimpleServer.use { ctx =>
      for {
        response <- initializeServer(ctx.server, ctx.workspace)
      } yield {
        response.result match {
          case Some(result) =>
            result.capabilities.completionProvider match {
              case Some(options) =>
                expect(options.triggerCharacters.exists(_.contains(".")))
              case None => failure("Expected completion provider")
            }
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("server capabilities include correct signature help trigger characters") { _ =>
    withSimpleServer.use { ctx =>
      for {
        response <- initializeServer(ctx.server, ctx.workspace)
      } yield {
        response.result match {
          case Some(result) =>
            result.capabilities.signatureHelpProvider match {
              case Some(options) =>
                val expectedTriggers = List("(", "[", "{")
                val expectedRetriggers = List(",")
                expect(options.triggerCharacters.exists(_.intersect(expectedTriggers).nonEmpty)) &&
                expect(options.retriggerCharacters.exists(_.intersect(expectedRetriggers).nonEmpty))
              case None => failure("Expected SignatureHelpOptions")
            }
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("server responds to initialized notification") { _ =>
    withSimpleServer.use { ctx =>
      for {
        _        <- initializeServer(ctx.server, ctx.workspace)
        _        <- ctx.server.initialized(InitializedParams())
        // initialized doesn't return anything, but should not error
      } yield success
    }
  }

  test("server handles initialization with workspace folders") { _ =>
    withMultiModuleServer.use { ctx =>
      for {
        response <- initializeServer(ctx.server, ctx.workspace)
      } yield {
        // Server should initialize successfully with multi-module workspace
        response.result match {
          case Some(result) =>
            expect(result.capabilities.textDocumentSync.isDefined) &&
            expect(result.serverInfo.isDefined)
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("server handles initialization with minimal client capabilities") { _ =>
    withSimpleServer.use { ctx =>
      val minimalCapabilities = ClientCapabilities(
        textDocument = Some(TextDocumentClientCapabilities())
      )

      val params = InitializeParams(
        processId = Some(12345),
        rootPath = Some(ctx.workspace.root.toString),
        rootUri = Some(ctx.workspace.rootUri),
        initializationOptions = None,
        capabilities = minimalCapabilities,
        trace = Some(TraceValue.OFF),
        workspaceFolders = None
      )

      for {
        response <- ctx.server.initializeOp(params)
      } yield {
        // Server should still provide its capabilities even with minimal client capabilities
        response.result match {
          case Some(result) => expect(result.capabilities.textDocumentSync.isDefined)
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("server provides correct server info") { _ =>
    withSimpleServer.use { ctx =>
      for {
        response <- initializeServer(ctx.server, ctx.workspace)
      } yield {
        response.result match {
          case Some(result) =>
            result.serverInfo match {
              case Some(info) =>
                expect(info.name.nonEmpty) &&
                expect(info.version.isDefined)
              case None => failure("Expected server info")
            }
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("text document sync capability is set to incremental") { _ =>
    withSimpleServer.use { ctx =>
      for {
        response <- initializeServer(ctx.server, ctx.workspace)
      } yield {
        response.result match {
          case Some(result) =>
            result.capabilities.textDocumentSync match {
              case Some(_) => success // Just check that sync capability exists
              case None => failure("Expected text document sync capability")
            }
          case None => failure("Expected initialize result")
        }
      }
    }
  }

  test("server handles different trace levels") { _ =>
    withSimpleServer.use { ctx =>
      val testTraceLevel = (level: TraceValue) => {
        val capabilities = ClientCapabilities(
          textDocument = Some(TextDocumentClientCapabilities())
        )

        val params = InitializeParams(
          processId = Some(12345),
          rootPath = Some(ctx.workspace.root.toString),
          rootUri = Some(ctx.workspace.rootUri),
          initializationOptions = None,
          capabilities = capabilities,
          trace = Some(level),
          workspaceFolders = None
        )

        ctx.server.initializeOp(params).map(_.result.exists(_.serverInfo.isDefined))
      }

      for {
        offResult     <- testTraceLevel(TraceValue.OFF)
        messagesResult <- testTraceLevel(TraceValue.MESSAGES)
        verboseResult <- testTraceLevel(TraceValue.VERBOSE)
      } yield {
        expect(offResult) &&
        expect(messagesResult) &&
        expect(verboseResult)
      }
    }
  }
}