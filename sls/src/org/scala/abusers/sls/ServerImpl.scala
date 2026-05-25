package org.scala.abusers
package sls

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
import jsonrpclib.smithy4sinterop.ServerEndpoints
import org.eclipse.lsp4j
import org.scala.abusers.csp.CspServer
import org.scala.abusers.pc.IOCancelTokens
import org.scala.abusers.pc.PresentationCompilerDTOInterop.*
import org.scala.abusers.pc.PresentationCompilerProvider
import org.scala.abusers.pc.ScalaVersion
import org.scala.abusers.profiling.runtime.ProfilingOps.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import smithy4s.schema.Schema

import java.net.URI
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.meta.pc.CancelToken
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.OffsetParams
import scala.meta.pc.RawPresentationCompiler
import scala.meta.pc.VirtualFileParams

import LoggingUtils.*
import ScalaBuildTargetInformation.*

class ServerImpl(
    pcProvider: PresentationCompilerProvider,
    cancelTokens: IOCancelTokens,
    diagnosticManager: DiagnosticManager,
    steward: ResourceSupervisor[IO],
    bspClientDeferred: Deferred[IO, BuildServer],
    cspClientDeferred: Deferred[IO, CspServer[IO]],
    lspClient: SlsLanguageClient[IO],
    computationQueue: ComputationQueue,
    textDocumentSyncManager: TextDocumentSyncManager,
    bspStateManager: BspStateManager,
    indexManager: index.IndexManager,
    symbolIndex: index.SymbolIndex,
)(using Tracer[IO], Meter[IO])
    extends SlsLanguageServer[IO] {

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  /* There can only be one client for one language-server */

  def initializeOp(params: lsp.InitializeParams): IO[lsp.InitializeOpOutput] = {
    val rootUri       = params.rootUri.getOrElse(sys.error("what now?"))
    val rootSourceUri = SourceUri(rootUri)
    (for {
      _         <- lspClient.logMessage("Ready to initialise!")
      _         <- importMillBsp(rootSourceUri)
      bspClient <- connectWithBloop(steward, diagnosticManager)
      cspClient <- connectWithCsp(steward)
      _         <- lspClient.logMessage("Connection with mill estabilished")
      _         <- lspClient.logMessage(s"$bspClient")
      response  <- bspClient.generic.buildInitialize(
        InitializeBuildParams(
          displayName = "mill",
          version = "0.0.0",
          bspVersion = "2.2.0-M2",
          rootUri = bsp.URI(rootUri),
          capabilities = bsp.BuildClientCapabilities(languageIds = List(bsp.LanguageId("scala"))),
        )
      )
      _ <- lspClient.logMessage(s"Response from bsp: $response")
      _ <- bspClient.generic.onBuildInitialized()
      _ <- bspClientDeferred.complete(bspClient)
      _ <- cspClientDeferred.complete(cspClient)
    } yield lsp.InitializeOpOutput(
      lsp
        .InitializeResult(
          capabilities = serverCapabilities,
          serverInfo = lsp.ServerInfo("Simple (Scala) Language Server").some,
        )
        .some
    )).guaranteeCase(s => lspClient.logMessage(s"closing initalize with $s"))
  }

  def initialized(params: lsp.InitializedParams): IO[Unit] =
    computationQueue.synchronously {
      for {
        _       <- bspStateManager.importBuild
        targets <- bspStateManager.getAllTargets
        _       <- List(
          indexManager.indexJdkSources(),
          indexManager.indexDependencies(targets),
          indexManager.indexExistingProjectArtifacts(targets),
        ).parSequence_.handleErrorWith { e =>
          IO(logger.error("Background indexing failed", e)) *>
            lspClient.logMessage(s"Indexing failed: ${e.getMessage}")
        }.start
      } yield ()
    }

  def textDocumentCompletionOp(params: lsp.CompletionParams): IO[lsp.TextDocumentCompletionOpOutput] =
    computationQueue.synchronously {
      Tracer[IO].span("completion").profilingSurround(handleCompletion(params))
    }
  def textDocumentDefinitionOp(params: lsp.DefinitionParams): IO[lsp.TextDocumentDefinitionOpOutput] =
    computationQueue.synchronously {
      Tracer[IO].span("go-to-defintion").profilingSurround(handleDefinition(params))
    }
  def textDocumentDidChange(params: lsp.DidChangeTextDocumentParams): IO[Unit] =
    computationQueue.synchronously {
      Tracer[IO].span("did-change").profilingSurround(handleDidChange(params))
    }
  def textDocumentDidClose(params: lsp.DidCloseTextDocumentParams): IO[Unit] =
    computationQueue.synchronously {
      Tracer[IO].span("did-close").profilingSurround(handleDidClose(params))
    }
  def textDocumentDidOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit] =
    computationQueue.synchronously {
      Tracer[IO].span("did-open").profilingSurround(handleDidOpen(params))
    }
  def textDocumentDidSave(params: lsp.DidSaveTextDocumentParams): IO[Unit] =
    computationQueue.synchronously {
      Tracer[IO].span("did-save").profilingSurround(handleDidSave(params))
    }
  def textDocumentHoverOp(params: lsp.HoverParams): IO[lsp.TextDocumentHoverOpOutput] =
    computationQueue.synchronously {
      Tracer[IO].span("hover").profilingSurround(handleHover(params))
    }
  def textDocumentInlayHintOp(params: lsp.InlayHintParams): IO[lsp.TextDocumentInlayHintOpOutput] =
    computationQueue.synchronously {
      Tracer[IO].span("inlay-hints").profilingSurround(handleInlayHints(params))
    }
  def textDocumentSignatureHelpOp(params: lsp.SignatureHelpParams): IO[lsp.TextDocumentSignatureHelpOpOutput] =
    computationQueue.synchronously {
      Tracer[IO].span("signature-help").profilingSurround(handleSignatureHelp(params))
    }

  def toPC(completionTriggerKind: Option[lsp.CompletionTriggerKind]): lsp4j.CompletionTriggerKind =
    completionTriggerKind match {
      case Some(lsp.CompletionTriggerKind.INVOKED)           => lsp4j.CompletionTriggerKind.Invoked
      case Some(lsp.CompletionTriggerKind.TRIGGER_CHARACTER) => lsp4j.CompletionTriggerKind.TriggerCharacter
      case Some(lsp.CompletionTriggerKind.TRIGGER_FOR_INCOMPLETE_COMPLETIONS) =>
        lsp4j.CompletionTriggerKind.TriggerForIncompleteCompletions
      case None => lsp4j.CompletionTriggerKind.TriggerCharacter
    }

  // // TODO: goto type definition with container types
  def handleCompletion(params: lsp.CompletionParams)(using SynchronizedState) =
    cancelTokens.mkCancelToken.use { token =>
      val uri      = summon[PositionWithURI[lsp.CompletionParams]].uri(params)
      val position = summon[PositionWithURI[lsp.CompletionParams]].position(params)
      for {
        docState <- textDocumentSyncManager.get(uri)
        offsetParams = toOffsetParams(position, docState, token)
        triggerKind  = toPC(params.context.map(_.triggerKind))
        result <- pcParamsRequest(params, (offsetParams, triggerKind))(pc =>
          params => pc.complete(params._1, params._2)
        )
      } yield lsp.TextDocumentCompletionOpOutput(
        convert[lsp4j.CompletionList, lsp.ListCompletionUnion](result).some
      )
    }

  def handleHover(params: lsp.HoverParams)(using SynchronizedState) =
    offsetParamsRequest(params)(_.hover).map { result =>
      lsp.TextDocumentHoverOpOutput(
        result.toScala.map(hoverSig => convert[lsp4j.Hover, lsp.Hover](hoverSig.toLsp()))
      )
    }

  def handleSignatureHelp(params: lsp.SignatureHelpParams)(using SynchronizedState) =
    offsetParamsRequest(params)(_.signatureHelp).map { result =>
      lsp.TextDocumentSignatureHelpOpOutput(
        convert[lsp4j.SignatureHelp, lsp.SignatureHelp](result).some
      )
    }

  def handleDefinition(params: lsp.DefinitionParams)(using SynchronizedState) =
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

  def virtualFileParams(uri0: SourceUri, content: String, token0: CancelToken): VirtualFileParams =
    new VirtualFileParams {
      override def text(): String                     = content
      override def token(): CancelToken               = token0
      override def uri(): URI                         = uri0.toURI
      override def shouldReturnDiagnostics(): Boolean = true
    }

  given Schema[List[lsp.InlayHint]] = Schema.list(lsp.InlayHint.schema)

  def handleInlayHints(params: lsp.InlayHintParams)(using SynchronizedState) = {
    val uri0 = summon[WithURI[lsp.InlayHintParams]].uri(params)

    cancelTokens.mkCancelToken.use { token0 =>
      for {
        docState <- textDocumentSyncManager.get(uri0)
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
          def uri(): java.net.URI                = uri0.toURI
        }

        result <- pcParamsRequest(params, inalyHintsParams)(_.inlayHints)
        converted = convert[java.util.List[lsp4j.InlayHint], List[lsp.InlayHint]](result)
      } yield lsp.TextDocumentInlayHintOpOutput(converted.some)
    }
  }

  // def handleInlayHintsRefresh(in: Invocation[Unit, IO]) = IO.pure(null)

  private def offsetParamsRequest[Params: PositionWithURI, Result](params: Params)(
      thunk: RawPresentationCompiler => OffsetParams => Result
  )(using SynchronizedState): IO[Result] = { // TODO Completion on context bound inserts []
    val uri      = summon[WithURI[Params]].uri(params)
    val position = summon[WithPosition[Params]].position(params)
    cancelTokens.mkCancelToken.use { token =>
      for {
        docState <- textDocumentSyncManager.get(uri)
        offsetParams = toOffsetParams(position, docState, token)
        result <- pcParamsRequest(params, offsetParams)(thunk)
      } yield result
    }
  }

  private def pcParamsRequest[Params: WithURI, Result, PcParams](params: Params, pcParams: PcParams)(
      thunk: RawPresentationCompiler => PcParams => Result
  )(using SynchronizedState): IO[Result] = { // TODO: Completion on context bound inserts []
    val uri = summon[WithURI[Params]].uri(params)
    for {
      info   <- bspStateManager.get(uri)
      pc     <- pcProvider.get(info)
      result <- IO.interruptible(thunk(pc)(pcParams))
    } yield result
  }

  def handleDidSave(params: lsp.DidSaveTextDocumentParams)(using SynchronizedState) = {

    def updateOutputClasspath(targetInfo: ScalaBuildTargetInformation, newClassesJar: AbsolutePath): IO[Unit] = {
      val updateClassJar =
        // FIXME: Can't read betasty from jars !!!
        // FIXME: Can't write be tasty into jars !!!
        // TODO: Migrate to FS2
        // TODO: Write FS2 utils for all IO operations
        // TODO: Use custom traces and spans here to extract timings and profile it
        IO.whenA(newClassesJar.exists) {
          IO(targetInfo.classesDir.deleteRecursively) *>
            UnzipUtils.unzipJarFromPath(
              fs2.io.file.Path.fromNioPath(newClassesJar.toNioPath),
              fs2.io.file.Path.fromNioPath(targetInfo.classesDir.toNioPath),
            ) *>
            pcProvider.invalidateCompilers()
        }

      computationQueue.pushSync(updateClassJar)
    }

    val uri = params.textDocument.sourceUri
    for {
      _    <- textDocumentSyncManager.didSave(params)
      info <- bspStateManager.get(uri)
      _    <- bspStateManager
        .compileWithCSP(uri)
        .flatMap { res =>
          updateOutputClasspath(info, AbsolutePath(res.outputJar)) *>
            indexManager
              .onCompilationComplete(info, res)
              .handleError(e => lspClient.logMessage(s"Failed to update index after compilation: ${e.getMessage()}"))
        }
        .timeout(60.seconds)
        .start
    } yield ()
  }

  def handleDidOpen(params: lsp.DidOpenTextDocumentParams)(using SynchronizedState) =
    textDocumentSyncManager.didOpen(params) *> bspStateManager.didOpen(lspClient, params)

  def handleDidClose(params: lsp.DidCloseTextDocumentParams)(using SynchronizedState) =
    textDocumentSyncManager.didClose(params)

  implicit val positionTransformer: Transformer[lsp4j.Position, lsp.Position] =
    Transformer.define[lsp4j.Position, lsp.Position].enableBeanGetters.buildTransformer

  implicit val rangeTransformer: Transformer[lsp4j.Range, lsp.Range] =
    Transformer.define[lsp4j.Range, lsp.Range].enableBeanGetters.buildTransformer

  val handleDidChange: SynchronizedState ?=> lsp.DidChangeTextDocumentParams => IO[Unit] = {
    val debounce = Debouncer(250.millis)

    def isSupported(info: ScalaBuildTargetInformation): Boolean = {
      import scala.math.Ordered.orderingToOrdered
      info.scalaVersion > ScalaVersion("3.7.2")
    }

    /** We want to debounce compiler diagnostics as they are expensive to compute and we can't really cancel them as
      * they are triggered by notification and AFAIK, LSP cancellation only works for requests.
      */
    def pcDiagnostics(info: ScalaBuildTargetInformation, uri: SourceUri): IO[Unit] =
      computationQueue.synchronously {
        cancelTokens.mkCancelToken.use { token =>
          for {
            textDocument <- textDocumentSyncManager.get(uri)
            pc           <- pcProvider.get(info)
            params = virtualFileParams(uri, textDocument.content, token)
            diags <- IO(pc.didChange(params))
            lspDiags = diags
              .into[List[lsp.Diagnostic]]
              .withFieldRenamed(_.everyItem.getRange, _.everyItem.range)
              .withFieldRenamed(_.everyItem.getMessage, _.everyItem.message)
              .enableOptionDefaultsToNone
              .transform
            _ <- diagnosticManager.didChange(lspClient, uri, lspDiags)
            _ <- IO
              .interruptible(pc.semanticdbTextDocument(uri.toURI, textDocument.content))
              .flatMap { bytes =>
                val (syms, refs) = index.SemanticdbIndexer.indexDocument(uri, bytes, info.displayName)
                indexManager.updateOpenFile(uri, syms, refs)
              }
              .handleErrorWith(e => IO(logger.warn(s"SemanticDB index update failed for $uri: ${e.getMessage}")))
          } yield ()
        }
      }

    params =>
      {
        for {
          _ <- textDocumentSyncManager.didChange(params)
          uri = params.textDocument.sourceUri
          info <- bspStateManager.get(uri)
        } yield (uri, info)
      }
        .flatMap { (uri, info) =>
          if isSupported(info) then debounce.debounce(pcDiagnostics(info, uri)) else IO.unit
        }
  }

  def textDocumentReferencesOp(params: lsp.ReferenceParams): IO[lsp.TextDocumentReferencesOpOutput] =
    computationQueue.synchronously {
      handleReferences(params)
    }

  private def handleReferences(
      params: lsp.ReferenceParams
  )(using SynchronizedState): IO[lsp.TextDocumentReferencesOpOutput] = {
    val uri      = params.textDocument.sourceUri
    val position = params.position
    cancelTokens.mkCancelToken.use { token =>
      for {
        docState <- textDocumentSyncManager.get(uri)
        offsetParams = toOffsetParams(position, docState, token)
        info      <- bspStateManager.get(uri)
        pc        <- pcProvider.get(info)
        defResult <- IO.interruptible(pc.definition(offsetParams))
        pcSymbol = defResult.symbol()
        targetId = index.SymbolId.fromSemanticDb(pcSymbol)
        _    <- IO(logger.info(s"References: pcSymbol=$pcSymbol, targetId=${targetId.render}"))
        refs <- symbolIndex.getReferences(targetId)
        _    <- IO(logger.info(s"References for ${targetId.render}: ${refs.size} refs"))
        _    <- symbolIndex.allReferenceTargets.flatMap(keys =>
          IO(logger.info(s"All reference keys in index (${keys.size}): ${keys.take(20).map(_.render)}"))
        )
      } yield lsp.TextDocumentReferencesOpOutput(
        if refs.isEmpty then None
        else
          Some(
            refs.map(ref =>
              lsp.Location(
                uri = ref.location.uri.toLspUri,
                range = lsp.Range(
                  start = lsp.Position(ref.location.startLine, ref.location.startCol),
                  end = lsp.Position(ref.location.endLine, ref.location.endCol),
                ),
              )
            )
          )
      )
    }
  }

  def workspaceSymbolOp(params: lsp.WorkspaceSymbolParams): IO[lsp.WorkspaceSymbolOpOutput] =
    IO.pure(lsp.WorkspaceSymbolOpOutput(None))

  def textDocumentRenameOp(params: lsp.RenameParams): IO[lsp.TextDocumentRenameOpOutput] =
    IO.pure(lsp.TextDocumentRenameOpOutput(None))

  def textDocumentPrepareRenameOp(params: lsp.PrepareRenameParams): IO[lsp.TextDocumentPrepareRenameOpOutput] =
    IO.pure(lsp.TextDocumentPrepareRenameOpOutput(None))

  def textDocumentImplementationOp(params: lsp.ImplementationParams): IO[lsp.TextDocumentImplementationOpOutput] =
    IO.pure(lsp.TextDocumentImplementationOpOutput(None))

  def textDocumentPrepareTypeHierarchyOp(
      params: lsp.TypeHierarchyPrepareParams
  ): IO[lsp.TextDocumentPrepareTypeHierarchyOpOutput] =
    IO.pure(lsp.TextDocumentPrepareTypeHierarchyOpOutput(None))

  def typeHierarchySupertypesOp(params: lsp.TypeHierarchySupertypesParams): IO[lsp.TypeHierarchySupertypesOpOutput] =
    IO.pure(lsp.TypeHierarchySupertypesOpOutput(None))

  def typeHierarchySubtypesOp(params: lsp.TypeHierarchySubtypesParams): IO[lsp.TypeHierarchySubtypesOpOutput] =
    IO.pure(lsp.TypeHierarchySubtypesOpOutput(None))

  def workspaceDidDeleteFiles(params: lsp.DeleteFilesParams): IO[Unit] = {
    val uris = params.files.map(f => SourceUri(f.uri)).toSet
    indexManager.onFilesDeleted(uris)
  }

  def slsDebugIndexOp(params: Option[SlsDebugIndexParams]): IO[SlsDebugIndexOpOutput] = {
    val query = params.flatMap(_.query).filter(_.nonEmpty)
    (for {
      projCount <- symbolIndex.projectSymbolCount
      depCount  <- symbolIndex.dependencySymbolCount
      fileCount <- symbolIndex.fileCount
      jarCount  <- symbolIndex.jarCount
      matching  <- query.fold(IO.pure(List.empty[index.IndexedSymbol]))(symbolIndex.searchSymbols)
    } yield SlsDebugIndexOpOutput(
      SlsDebugIndexResult(
        projectSymbolCount = Some(projCount),
        dependencySymbolCount = Some(depCount),
        projectFileCount = Some(fileCount),
        indexedJarCount = Some(jarCount),
        matchingSymbols = Some(matching.take(100).map(toDebugSymbol)),
      ).some
    )).timeout(10.seconds)
  }

  private def toDebugSymbol(s: index.IndexedSymbol): DebugSymbol =
    DebugSymbol(
      id = s.id.render,
      name = s.name,
      kind = s.kind.toString,
      owner = s.owner.map(_.render),
      origin = Some(s.origin match {
        case index.SymbolOrigin.ProjectTasty(bt, uri)      => s"project:$bt ($uri)"
        case index.SymbolOrigin.ProjectJavaSource(bt, uri) => s"project-java:$bt ($uri)"
        case index.SymbolOrigin.DependencyClassfile(jar)   => s"dep:$jar"
        case index.SymbolOrigin.DependencySource(jar, uri) => s"dep-src:$jar ($uri)"
        case index.SymbolOrigin.JdkSource(zip, uri)        => s"jdk-src:$zip ($uri)"
      }),
      location = s.location.map(l => s"${l.uri}:${l.startLine}:${l.startCol}"),
    )

  private def serverCapabilities: lsp.ServerCapabilities =
    lsp.ServerCapabilities(
      textDocumentSync = lsp.TextDocumentSyncUnion.case1(lsp.TextDocumentSyncKind.INCREMENTAL).some,
      completionProvider = lsp.CompletionOptions(triggerCharacters = List(".").some).some,
      hoverProvider = lsp.BooleanOrHoverOptions.case1(lsp.HoverOptions()).some,
      signatureHelpProvider = lsp
        .SignatureHelpOptions(None, List("(", "[", "{").some, List(",").some)
        .some, // FIXME: signature help on List triggers cats effect because of extension methods
      definitionProvider = lsp.BooleanOrDefinitionOptions.case1(lsp.DefinitionOptions(None)).some,
      referencesProvider = lsp.BooleanOrReferenceOptions.case1(lsp.ReferenceOptions(None)).some,
      inlayHintProvider = lsp.BooleanOrInlayHintOptions.case1(lsp.InlayHintOptions(resolveProvider = false.some)).some,
    )

  private def connectWithBloop(
      steward: ResourceSupervisor[IO],
      diagnosticManager: DiagnosticManager,
  ): IO[BuildServer] = {
    val bspClientRes = for {
      _       <- Resource.eval(lspClient.logMessage(s"Starting connection to mill BSP server"))
      channel <- FS2Channel
        .resource[IO]()
        .flatMap(_.withEndpoints(bspClientHandler(lspClient, diagnosticManager)))
      process <- ProcessBuilder("./mill", "--bsp").spawn[IO]
      _       <- Resource.eval(IO.sleep(1.seconds) *> lspClient.logMessage(s"Trying to connect to mill BSP server"))
      client  <- makeBspClient(process, channel, msg => lspClient.logMessage(s"reporting raw: $msg"))
    } yield client

    steward.acquire(bspClientRes)
  }

  private def connectWithCsp(
      steward: ResourceSupervisor[IO]
  ): IO[CspServer[IO]] = {
    import jsonrpclib.fs2.*
    val client = for {
      channel <- FS2Channel
        .resource[IO](cancelTemplate = None)
        .flatMap(
          _.withEndpoints(
            ServerEndpoints(ZincCspClient()).toOption.getOrElse(sys.error("Couldn't create ServerEndpoints"))
          )
        )
      zincJar    <- extractZincCliJar
      cspProcess <- fs2.io.process.ProcessBuilder("java", "-jar", zincJar.toString).spawn[IO]
      client <- ZincCspClient.makeCspClient(cspProcess, channel, msg => lspClient.logDebug(s"reporting raw CSP: $msg"))
    } yield client

    steward.acquire(client)
  }

  private def extractZincCliJar: Resource[IO, fs2.io.file.Path] =
    Resource.make(
      for {
        tempDir <- IO(java.nio.file.Files.createTempDirectory("sls-zinc"))
        jarPath = fs2.io.file.Path.fromNioPath(tempDir.resolve("zincCli.jar"))
        inputStream <- IO(
          Option(getClass.getClassLoader.getResourceAsStream("zinc-cli/zincCli.jar"))
            .getOrElse(sys.error("zincCli.jar not found in classpath resources"))
        )
        _ <- fs2.io
          .readInputStream(IO.pure(inputStream), 8192)
          .through(Files[IO].writeAll(jarPath))
          .compile
          .drain
      } yield jarPath
    )(jarPath => IO(AbsolutePath(jarPath.toNioPath.getParent).deleteRecursively))

  def importMillBsp(rootUri: SourceUri) = {
    val millExec = "./mill" // TODO if mising then findMillExec()
    ProcessBuilder(millExec, "mill.bsp.BSP/install")
      .withWorkingDirectory(rootUri.toFs2Path)
      .spawn[IO]
      .use { process =>
        val logStdout = process.stdout
        val logStderr = process.stderr

        val allOutput = logStdout
          .merge(logStderr)
          .through(text.utf8.decode)
          .through(text.lines)

        allOutput
          .evalMap(lspClient.logMessage)
          .compile
          .drain
      }
  }
}
