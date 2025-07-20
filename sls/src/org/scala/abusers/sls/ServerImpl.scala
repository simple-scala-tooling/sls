package org.scala.abusers.sls

import bsp.InitializeBuildParams
import cats.effect.kernel.Deferred
import cats.effect.kernel.Resource
import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Files
import fs2.io.process.ProcessBuilder
import fs2.text
import io.scalaland.chimney.dsl._
import io.scalaland.chimney.javacollections._
import io.scalaland.chimney.Transformer
import jsonrpclib.fs2.FS2Channel
import org.eclipse.lsp4j
import org.scala.abusers.pc.IOCancelTokens
import org.scala.abusers.pc.PresentationCompilerDTOInterop.*
import org.scala.abusers.pc.PresentationCompilerProvider
import org.scala.abusers.pc.ScalaVersion
import smithy4s.schema.Schema
import util.chaining.*

import java.net.URI
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.meta.pc.CancelToken
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.VirtualFileParams

import LoggingUtils.*
import ScalaBuildTargetInformation.*

class ServerImpl(
    stateManager: StateManager,
    pcProvider: PresentationCompilerProvider,
    cancelTokens: IOCancelTokens,
    diagnosticManager: DiagnosticManager,
    steward: ResourceSupervisor[IO],
    bspClientDeferred: Deferred[IO, BuildServer],
    lspClient: SlsLanguageClient[IO],
) extends SlsLanguageServer[IO] {

  /* There can only be one client for one language-server */

  def initializeOp(params: lsp.InitializeParams): IO[lsp.InitializeOpOutput] = {
    val rootUri  = params.rootUri.getOrElse(sys.error("what now?"))
    val rootPath = os.Path(java.net.URI.create(rootUri).getPath())
    (for {
      _         <- lspClient.logMessage("Ready to initialise!")
      _         <- importMillBsp(rootPath, lspClient)
      bspClient <- connectWithBloop(steward, diagnosticManager)
      _         <- lspClient.logMessage("Connection with bloop estabilished")
      response <- bspClient.generic.buildInitialize(
        InitializeBuildParams(
          displayName = "bloop",
          version = "0.0.0",
          bspVersion = "2.1.0",
          rootUri = bsp.URI(rootUri),
          capabilities = bsp.BuildClientCapabilities(languageIds = List(bsp.LanguageId("scala"))),
        )
      )
      _ <- lspClient.logMessage(s"Response from bsp: $response")
      _ <- bspClient.generic.onBuildInitialized()
      _ <- bspClientDeferred.complete(bspClient)
    } yield lsp.InitializeOpOutput(
      lsp
        .InitializeResult(
          capabilities = serverCapabilities,
          serverInfo = lsp.ServerInfo("Simple (Scala) Language Server", Some("0.0.1")).some,
        )
        .some
    )).guaranteeCase(s => lspClient.logMessage(s"closing initalize with $s"))
  }

  def initialized(params: lsp.InitializedParams): IO[Unit] = stateManager.importBuild
  def textDocumentCompletionOp(params: lsp.CompletionParams): IO[lsp.TextDocumentCompletionOpOutput] = handleCompletion(
    params
  )
  def textDocumentDefinitionOp(params: lsp.DefinitionParams): IO[lsp.TextDocumentDefinitionOpOutput] = handleDefinition(
    params
  )
  def textDocumentDidChange(params: lsp.DidChangeTextDocumentParams): IO[Unit]        = handleDidChange(params)
  def textDocumentDidClose(params: lsp.DidCloseTextDocumentParams): IO[Unit]          = handleDidClose(params)
  def textDocumentDidOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit]            = handleDidOpen(params)
  def textDocumentDidSave(params: lsp.DidSaveTextDocumentParams): IO[Unit]            = handleDidSave(params)
  def textDocumentHoverOp(params: lsp.HoverParams): IO[lsp.TextDocumentHoverOpOutput] = handleHover(params)
  def textDocumentInlayHintOp(params: lsp.InlayHintParams): IO[lsp.TextDocumentInlayHintOpOutput] = handleInlayHints(
    params
  )
  def textDocumentSignatureHelpOp(params: lsp.SignatureHelpParams): IO[lsp.TextDocumentSignatureHelpOpOutput] =
    handleSignatureHelp(params)

  // // TODO: goto type definition with container types
  def handleCompletion(params: lsp.CompletionParams) =
    offsetParamsRequest(params)(_.complete).map { result =>
      lsp.TextDocumentCompletionOpOutput(
        convert[lsp4j.CompletionList, lsp.ListCompletionUnion](result).some
      )
    }

  def handleHover(params: lsp.HoverParams) =
    offsetParamsRequest(params)(_.hover).map { result =>
      lsp.TextDocumentHoverOpOutput(
        result.toScala.map(hoverSig => convert[lsp4j.Hover, lsp.Hover](hoverSig.toLsp()))
      )
    }

  def handleSignatureHelp(params: lsp.SignatureHelpParams) =
    offsetParamsRequest(params)(_.signatureHelp).map { result =>
      lsp.TextDocumentSignatureHelpOpOutput(
        convert[lsp4j.SignatureHelp, lsp.SignatureHelp](result).some
      )
    }

  def handleDefinition(params: lsp.DefinitionParams) =
    offsetParamsRequest(params)(_.definition).map { result =>
      lsp.TextDocumentDefinitionOpOutput(
        result
          .locations()
          .asScala
          .headOption
          .map(definition =>
            convert[lsp4j.Location, lsp.DefinitionOrListOfDefinitionLink](definition)
          ) // FIXME: missing completion on lsp.TextDocumentDefinitionOpOutput
      )
    }

  def virtualFileParams(uri0: URI, content: String, token0: CancelToken): VirtualFileParams = new VirtualFileParams {
    override def text(): String       = content
    override def token(): CancelToken = token0
    override def uri(): URI           = uri0
  }

  given Schema[List[lsp.InlayHint]] = Schema.list(lsp.InlayHint.schema)

  def handleInlayHints(params: lsp.InlayHintParams) = {
    val uri0 = summon[WithURI[lsp.InlayHintParams]].uri(params)

    cancelTokens.mkCancelToken.use { token0 =>
      for {
        docState <- stateManager.getDocumentState(uri0)
        inalyHintsParams = new InlayHintsParams {
          import docState.*
          def implicitConversions(): Boolean     = true
          def implicitParameters(): Boolean      = true
          def inferredTypes(): Boolean           = true
          def typeParameters(): Boolean          = true
          def offset(): Int                      = params.range.start.toOffset
          def endOffset(): Int                   = params.range.end.toOffset
          def text(): String                     = content
          def token(): scala.meta.pc.CancelToken = token0
          def uri(): java.net.URI                = uri0
        }

        result <- pcParamsRequest(params, inalyHintsParams)(_.inlayHints)
        converted = convert[java.util.List[lsp4j.InlayHint], List[lsp.InlayHint]](result)
      } yield lsp.TextDocumentInlayHintOpOutput(converted.some)
    }
  }

  // def handleInlayHintsRefresh(in: Invocation[Unit, IO]) = IO.pure(null)

  private def offsetParamsRequest[Params: PositionWithURI, Result](params: Params)(
      thunk: PresentationCompiler => OffsetParams => CompletableFuture[Result]
  ): IO[Result] = { // TODO Completion on context bound inserts []
    val uri      = summon[WithURI[Params]].uri(params)
    val position = summon[WithPosition[Params]].position(params)
    cancelTokens.mkCancelToken.use { token =>
      for {
        docState <- stateManager.getDocumentState(uri)
        offsetParams = toOffsetParams(position, docState, token)
        result <- pcParamsRequest(params, offsetParams)(thunk)
      } yield result
    }
  }

  private def pcParamsRequest[Params: WithURI, Result, PcParams](params: Params, pcParams: PcParams)(
      thunk: PresentationCompiler => PcParams => CompletableFuture[Result]
  ): IO[Result] = { // TODO Completion on context bound inserts []
    val uri = summon[WithURI[Params]].uri(params)
    for {
      info   <- stateManager.getBuildTargetInformation(uri)
      pc     <- pcProvider.get(info)
      result <- IO.fromCompletableFuture(IO(thunk(pc)(pcParams)))
    } yield result
  }

  def handleDidSave(params: lsp.DidSaveTextDocumentParams) = stateManager.didSave(params)

  def handleDidOpen(params: lsp.DidOpenTextDocumentParams) = stateManager.didOpen(params)

  def handleDidClose(params: lsp.DidCloseTextDocumentParams) = stateManager.didClose(params)

  implicit val positionTransformer: Transformer[lsp4j.Position, lsp.Position] =
    Transformer.define[lsp4j.Position, lsp.Position].enableBeanGetters.buildTransformer

  implicit val rangeTransformer: Transformer[lsp4j.Range, lsp.Range] =
    Transformer.define[lsp4j.Range, lsp.Range].enableBeanGetters.buildTransformer

  val handleDidChange: lsp.DidChangeTextDocumentParams => IO[Unit] = {
    val debounce = Debouncer(300.millis)

    def isSupported(info: ScalaBuildTargetInformation): Boolean = {
      import scala.math.Ordered.orderingToOrdered
      info.scalaVersion > ScalaVersion("3.7.2")
    }

    /** We want to debounce compiler diagnostics as they are expensive to compute and we can't really cancel them as
      * they are triggered by notification and AFAIK, LSP cancellation only works for requests.
      */
    def pcDiagnostics(info: ScalaBuildTargetInformation, uri: URI): IO[Unit] =
      cancelTokens.mkCancelToken.use { token =>
        for {
          _            <- lspClient.logDebug("Getting PresentationCompiler diagnostics")
          textDocument <- stateManager.getDocumentState(uri)
          pc           <- pcProvider.get(info)
          params = virtualFileParams(uri, textDocument.content, token)
          diags <- IO.fromCompletableFuture(IO(pc.didChange(params)))
          lspDiags = diags
            .into[List[lsp.Diagnostic]]
            .withFieldRenamed(_.everyItem.getRange, _.everyItem.range)
            .withFieldRenamed(_.everyItem.getMessage, _.everyItem.message)
            .enableOptionDefaultsToNone
            .transform
          _ <- diagnosticManager.didChange(lspClient, uri.toString, lspDiags)
        } yield ()
      }

    params =>
      for {
        _ <- stateManager.didChange(params)
        _ <- lspClient.logDebug("Updated DocumentState")
        uri = URI(params.textDocument.uri)
        info <- stateManager.getBuildTargetInformation(uri)
        _    <- if isSupported(info) then debounce.debounce(pcDiagnostics(info, uri)) else IO.unit
      } yield ()
  }

  private def serverCapabilities: lsp.ServerCapabilities =
    lsp.ServerCapabilities(
      textDocumentSync = lsp.TextDocumentSyncUnion.case1(lsp.TextDocumentSyncKind.INCREMENTAL).some,
      completionProvider = lsp.CompletionOptions(triggerCharacters = List(".").some).some,
      hoverProvider = lsp.BooleanOrHoverOptions.case1(lsp.HoverOptions()).some,
      signatureHelpProvider = lsp
        .SignatureHelpOptions(None, List("(", "[", "{").some, List(",").some)
        .some, // FIXME signature help on List triggers cats effect because of extension methods
      definitionProvider = lsp.BooleanOrDefinitionOptions.case1(lsp.DefinitionOptions(None)).some,
      inlayHintProvider = lsp.BooleanOrInlayHintOptions.case1(lsp.InlayHintOptions(resolveProvider = false.some)).some,
    )

  private def connectWithBloop(
      steward: ResourceSupervisor[IO],
      diagnosticManager: DiagnosticManager,
  ): IO[BuildServer] = {
    def bspProcess(socketFile: os.Path) =
      ProcessBuilder("bloop", "bsp", "--socket", socketFile.toNIO.toString())
        .spawn[IO]
        .flatMap { bspSocketProc =>
          IO.deferred[Unit].toResource.flatMap { started =>
            val waitForStart: fs2.Pipe[IO, Byte, Nothing] =
              _.through(fs2.text.utf8.decode)
                .through(fs2.text.lines)
                .find(_.contains("The server is listening for incoming connections"))
                .foreach(_ => started.complete(()).void)
                .drain

            bspSocketProc.stdout
              .observe(waitForStart)
              .merge(bspSocketProc.stderr)
              .through(text.utf8.decode)
              .through(text.lines)
              .evalMap(s => lspClient.logMessage(s"[bloop] $s"))
              .onFinalizeCase(c => lspClient.sendMessage(s"Bloop process terminated $c"))
              .compile
              .drain
              .background
            // wait for the started message before proceeding
              <* started.get.toResource
          }

        }
        .as(socketFile)

    val bspClientRes = for {
      temp <- Files[IO]
        .tempDirectory(
          dir = None,
          prefix = "sls",
          permissions = None,
        )
        .map(_.toNioPath.pipe(os.Path(_))) // TODO Investigate possible clashes during reconnection
      socketFile = temp / "bloop.socket"
      socketPath <- bspProcess(socketFile)
      _          <- Resource.eval(IO.sleep(1.seconds) *> lspClient.logMessage(s"Looking for socket at $socketPath"))
      channel <- FS2Channel
        .resource[IO]()
        .flatMap(_.withEndpoints(bspClientHandler(lspClient, diagnosticManager)))
      client <- makeBspClient(socketPath.toString, channel, msg => lspClient.logDebug(s"reporting raw: $msg"))
    } yield client

    steward.acquire(bspClientRes)
  }

  private def findMillExec(rootPath: os.Path): IO[String] = {
    def searchForMill(current: fs2.io.file.Path): IO[fs2.io.file.Path] = {
      val millFile = current / "mill"
      Files[IO].exists(millFile).flatMap { exists =>
        if (exists) {
          // Verify it's a regular file (not directory) and executable
          Files[IO].isRegularFile(millFile).flatMap { isFile =>
            if (isFile) {
              Files[IO].getPosixPermissions(millFile).flatMap { perms =>
                if (perms.toString.contains("x")) IO.pure(millFile)
                else {
                  val parent = current.parent
                  if (parent.isEmpty)
                    IO.raiseError(
                      new RuntimeException(s"Could not find executable mill in any parent directory of ${current}")
                    )
                  else
                    searchForMill(parent.get)
                }
              }
            } else {
              val parent = current.parent
              if (parent.isEmpty)
                IO.raiseError(
                  new RuntimeException(s"Could not find executable mill in any parent directory of ${current}")
                )
              else
                searchForMill(parent.get)
            }
          }
        } else {
          val parent = current.parent
          if (parent.isEmpty)
            IO.raiseError(new RuntimeException(s"Could not find mill executable in any parent directory of ${current}"))
          else
            searchForMill(parent.get)
        }
      }
    }

    // Start search from current working directory instead of rootPath (which is temp dir)
    Files[IO].currentWorkingDirectory.flatMap(searchForMill).map(_.toString)
  }

  def importMillBsp(rootPath: os.Path, client: SlsLanguageClient[IO]) =
    findMillExec(rootPath).flatMap { millExec =>
      ProcessBuilder(millExec, "--import", "ivy:com.lihaoyi::mill-contrib-bloop:", "mill.contrib.bloop.Bloop/install")
        .withWorkingDirectory(fs2.io.file.Path.fromNioPath(rootPath.toNIO))
        .spawn[IO]
        .use { process =>
          val logStdout = process.stdout
          val logStderr = process.stderr

          val allOutput = logStdout
            .merge(logStderr)
            .through(text.utf8.decode)
            .through(text.lines)

          allOutput
            .evalMap(client.logMessage)
            .compile
            .drain
        }
    }
}
