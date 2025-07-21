package org.scala.abusers.sls.integration

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import lsp.*
import org.scala.abusers.pc.PresentationCompilerProvider
import org.scala.abusers.sls.*
import org.scala.abusers.sls.integration.utils.TestLSPClient
import org.scala.abusers.sls.integration.utils.TestWorkspace
import weaver.SimpleIOSuite

import java.net.URI

abstract class LSPIntegrationTestSuite extends SimpleIOSuite {

  case class TestServerContext(
      server: ServerImpl,
      client: TestLSPClient,
      workspace: TestWorkspace,
  )

  protected def withServer(workspace: Resource[IO, TestWorkspace]): Resource[IO, TestServerContext] =
    for {
      workspace <- workspace
      client    <- TestLSPClient.create.toResource
      serverCtx <- createServer(client, workspace)
    } yield TestServerContext(serverCtx, client, workspace)

  protected def withSimpleServer: Resource[IO, TestServerContext] =
    withServer(TestWorkspace.withSimpleScalaProject)

  protected def withMultiModuleServer: Resource[IO, TestServerContext] =
    withServer(TestWorkspace.withMultiModuleProject)

  protected def withMockBSPServer(workspace: Resource[IO, TestWorkspace]): Resource[IO, TestServerContext] =
    for {
      workspace <- workspace
      client    <- TestLSPClient.create.toResource
      mockBSP   <- org.scala.abusers.sls.integration.bsp.utils.MockBSPServer.withDefaultTargets.toResource
      serverCtx <- createServerWithMockBSP(client, workspace, mockBSP)
    } yield TestServerContext(serverCtx, client, workspace)

  protected def withLanguageFeaturesServer: Resource[IO, TestServerContext] =
    withMockBSPServer(TestWorkspace.withSimpleScalaProject)

  private def createServer(client: TestLSPClient, workspace: TestWorkspace): Resource[IO, ServerImpl] =
    for {
      steward           <- ResourceSupervisor[IO]
      pcProvider        <- PresentationCompilerProvider.instance.toResource
      textDocumentSync  <- TextDocumentSyncManager.instance.toResource
      bspClientDeferred <- cats.effect.kernel.Deferred[IO, BuildServer].toResource
      bspStateManager   <- BspStateManager.instance(client, BuildServer.suspend(bspClientDeferred.get)).toResource
      stateManager      <- StateManager.instance(client, textDocumentSync, bspStateManager).toResource
      cancelTokens      <- org.scala.abusers.pc.IOCancelTokens.instance
      diagnosticManager <- DiagnosticManager.instance.toResource
    } yield ServerImpl(stateManager, pcProvider, cancelTokens, diagnosticManager, steward, bspClientDeferred, client)

  protected def createServerWithMockBSP(
      client: TestLSPClient,
      workspace: TestWorkspace,
      mockBSP: org.scala.abusers.sls.integration.bsp.utils.MockBSPServer,
  ): Resource[IO, ServerImpl] =
    for {
      steward           <- ResourceSupervisor[IO]
      pcProvider        <- PresentationCompilerProvider.instance.toResource
      textDocumentSync  <- TextDocumentSyncManager.instance.toResource
      bspClientDeferred <- cats.effect.kernel.Deferred[IO, BuildServer].toResource
      bspServer = mockBSP.createBuildServer
      _                 <- bspClientDeferred.complete(bspServer).toResource
      bspStateManager   <- BspStateManager.instance(client, bspServer).toResource
      stateManager      <- StateManager.instance(client, textDocumentSync, bspStateManager).toResource
      cancelTokens      <- org.scala.abusers.pc.IOCancelTokens.instance
      diagnosticManager <- DiagnosticManager.instance.toResource
    } yield ServerImpl(stateManager, pcProvider, cancelTokens, diagnosticManager, steward, bspClientDeferred, client)

  // Helper methods for common test operations

  protected def initializeServer(server: ServerImpl, workspace: TestWorkspace): IO[InitializeOpOutput] = {
    val capabilities = ClientCapabilities(
      textDocument = Some(
        TextDocumentClientCapabilities(
          synchronization = Some(
            TextDocumentSyncClientCapabilities(
              dynamicRegistration = Some(false),
              willSave = Some(true),
              willSaveWaitUntil = Some(true),
              didSave = Some(true),
            )
          ),
          completion = Some(
            CompletionClientCapabilities(
              dynamicRegistration = Some(false),
              completionItem = Some(ClientCompletionItemOptions()),
            )
          ),
          hover = Some(
            HoverClientCapabilities(
              dynamicRegistration = Some(false)
            )
          ),
          signatureHelp = Some(
            SignatureHelpClientCapabilities(
              dynamicRegistration = Some(false)
            )
          ),
          definition = Some(
            DefinitionClientCapabilities(
              dynamicRegistration = Some(false),
              linkSupport = Some(true),
            )
          ),
        )
      )
    )

    val params = InitializeParams(
      processId = Some(12345),
      rootPath = Some(workspace.root.toString),
      rootUri = Some(workspace.rootUri),
      initializationOptions = None,
      capabilities = capabilities,
      trace = Some(TraceValue.VERBOSE),
      workspaceFolders = Some(
        List(
          WorkspaceFolder(
            uri = workspace.rootUri,
            name = "test-workspace",
          )
        )
      ),
    )

    server.initializeOp(params)
  }

  protected def openDocument(server: ServerImpl, fileUri: String, content: String): IO[Unit] = {
    val params = DidOpenTextDocumentParams(
      TextDocumentItem(
        uri = fileUri,
        languageId = LanguageKind.SCALA,
        version = 1,
        text = content,
      )
    )
    server.textDocumentDidOpen(params)
  }

  protected def readFileContent(path: Path): IO[String] =
    fs2.io.file.Files[IO].readUtf8(path).compile.string

  protected def makeTextChange(
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

  protected def makePosition(line: Int, character: Int): Position =
    Position(line, character)

  protected def makeRange(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range =
    Range(
      start = Position(startLine, startChar),
      end = Position(endLine, endChar),
    )
}
