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
import lsp.DefinitionOrListOfDefinitionLink
import scala.meta.pc.DefinitionResult
import java.nio.file.Path
import java.util.jar.JarFile
import cats.data.OptionT
import cats.data.ZipStream
import java.util.zip.ZipInputStream
import fs2.compression.Compression
import os.RelPath
import scala.meta.pc.TastyInformation
import java.nio.file.Paths

class ServerImpl(
    stateManager: StateManager,
    pcProvider: PresentationCompilerProvider,
    cancelTokens: IOCancelTokens,
    diagnosticManager: DiagnosticManager,
    steward: ResourceSupervisor[IO],
    bspClientDeferred: Deferred[IO, BuildServer],
    lspClient: SlsLanguageClient[IO]
) extends SlsLanguageServer[IO] {

  /* There can only be one client for one language-server */
  val rootPathDeferred: Deferred[IO, os.Path] = Deferred.unsafe[IO, os.Path]

  def initializeOp(params: lsp.InitializeParams): IO[lsp.InitializeOpOutput] = {
    val rootUri  = params.rootUri.getOrElse(sys.error("what now?"))
    val rootPath = os.Path(java.net.URI.create(rootUri).getPath())
    (for {
      _         <- rootPathDeferred.complete(rootPath)
      _         <- lspClient.logMessage("Ready to initialise!")
      buildTool <- detectBuildTool(rootPath)
      _         <- lspClient.logMessage(s"Detected build tool: $buildTool")
      bspClient <- buildTool match {
        case Mill =>
          setupBuildTool(rootPath, lspClient) *> connectWithBloop(steward, diagnosticManager)
        case ScalaCli =>
          setupBuildTool(rootPath, lspClient) *> connectWithScalaCli(rootPath, steward, diagnosticManager)
      }
      serverName = buildTool match {
        case Mill => "bloop"
        case ScalaCli => "scala-cli"
      }
      _ <- lspClient.logMessage(s"Connection with $serverName established")
      response <- bspClient.generic.buildInitialize(
        InitializeBuildParams(
          displayName = serverName,
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
      lsp .InitializeResult( capabilities = serverCapabilities,
          serverInfo = lsp.ServerInfo("Simple (Scala) Language Server").some,
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

  class TastyIndex() {
  }

  /**
   * Scala 2 dependency porvider will return the location of the class file and class jar and fully qualified path of the symbol.
   * It will not return the source file, but we can find it in the classfile. (I hope that this is the case)
   *
   * I believe this can be done by extracting line number table for the queried symbol definition site
   */
  // class Scala2DependencyProvider() extends DependencyProvider {
  // }

  // /**
  //  * Java source can be handled in the same way as Scala 2, but we can also use JDT to find the position of the queried symbol
  //  * We will know its exact fully qualifeid path + know the classjar path.
  //  */
  // class JavaDependencyProvider() extends DependencyProvider {

  // }


  /**
   * Scala 3 source dependency provider handles navigation in both project source and inside its dependencies.
   *
   * Its implementation is based on fact that compiler already has all informations about source of the files
   * The rules are:
   * - If the definition is in our own project source file we directly receive the location of the source file
   * - If the definition is in the dependency, we receive the location of the tasty jar, tasty file inside the jar
   *     and the location of the source file inside the source jar. By finding parent directory of the tasty jar, we can
   *     find the source jar file which then is extracted to the temporary directory and returned as a location.
   *
   *     This is done, beacuse clients do not support jar, jdt shemas.
   *
   * - If the definition is in the dependency, and we're currently in the dependency source it will work the same way,
   *     because tasty has all necessary information about the source file.
   *
   *     To handle this though, we should write a custom tasty parser that will find symbol at queried position
   *     Other option is to modify presentation compiler to allow us to load tasty pickle and do not compile source at all.
   *     Then proceed as usual.
   *
   */
  class Scala3DependencyProvider() extends DependencyProvider {
    val sourceDir = fs2.Stream.eval(rootPathDeferred.get.map(_ / ".sls" / "sources"))
    // Scala 3 will return 2 locations, one which represents the in jar source and second which represents the tasty file


    // TODO: Add a way to unpack source jars to either global cache or workspace cache

    def fromURI(uri: String): OptionT[IO, fs2.io.file.Path] = {
      OptionT.pure[IO](URI.create(uri).getPath).map(fs2.io.file.Path.apply)
    }

    def findScalaSource(path: Option[String], range: lsp.Range): OptionT[IO, List[lsp.Location]] = {
      OptionT
        .fromOption[IO](path)
        .flatMap(fromURI)
        .filterF(Files[IO].exists)
        .map { fs2Path =>
          lsp.Location(
            uri = fs2Path.toNioPath.toUri.toString,
            range = range
          ) :: Nil
        }
    }

    def findSourceJar(tastyJarPath: fs2.io.file.Path): OptionT[IO, fs2.io.file.Path] = {
      import org.scala.abusers.sls.NioConverter.asNio
      for {
        dependencyInfo <- OptionT(stateManager.getDependencyInfo(tastyJarPath.toNioPath.toUri))
        sourceJar <- dependencyInfo.data.artifacts.find(_.classifier.contains("sources")).map { artifact =>
          fs2.io.file.Path(artifact.uri.asNio.getPath())
        }.toOptionT[IO].filterF(Files[IO].exists)
      } yield (sourceJar)
    }

    def findLocationsFromTasty(tastyJarPath: Option[String], inTastyJarPath: Option[String], range: lsp.Range): OptionT[IO, List[lsp.Location]] = {
      for {
        tastyJarPath <- OptionT.fromOption[IO](tastyJarPath).flatMap(fromURI).filterF(Files[IO].exists)
        inTastyJarPath <- OptionT.fromOption[IO](inTastyJarPath).flatMap(fromURI)
        sourceJarPath <- findSourceJar(tastyJarPath)
        cacheDir <- OptionT.liftF(rootPathDeferred.get.map(_ / ".sls" / "sources")).map(_.toNIO).map(fs2.io.file.Path.fromNioPath)
        thisJarCacheDir = cacheDir.resolve(sourceJarPath.fileName)
        _ <- OptionT.liftF(UnzipUtils.unzipJarFromPath(sourceJarPath, thisJarCacheDir))
      } yield {
        lsp.Location(
          uri = thisJarCacheDir.resolve(inTastyJarPath.toString.stripSuffix(".tasty") + ".scala").toString,
          range = range
        ) :: Nil
      }
    }

    override def dependencyForResult(dep: TastyInformation): IO[List[lsp.Location]] = {
      // we will probably unpack source jars somewhere so we can explore them, we can do this lazily, one file at a time to save disk, we can also unpack it to tmp ? possibly in global cache ?

      val range = dep.range().toScala.map(_.into[lsp.Range].enableBeanGetters.transform).getOrElse(sys.error("No range provided in TastyInformation"))
      findScalaSource(dep.sourcePath().toScala, range).orElse {
        findLocationsFromTasty(
          dep.tastyJarPath().toScala,
          dep.inTastyJarPath().toScala,
          range
        )
      }.getOrElse(Nil)
    }
  }

  trait DependencyProvider {
    def dependencyForResult(dep: TastyInformation): IO[List[lsp.Location]]
  }

  def handleDefinition(params: lsp.DefinitionParams) =
    offsetParamsRequest(params)(_.tastyInfo).flatMap { result =>
      // we will need different handlers for java, scala 2 and scala 3
      lspClient.logMessage(result.toString) >>
        new Scala3DependencyProvider().dependencyForResult(result).map { locations =>
          lsp.TextDocumentDefinitionOpOutput(
            DefinitionOrListOfDefinitionLink.Case0Case(lsp.Definition.Case1Case(locations)).some
          )
        }
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
      info.scalaVersion > ScalaVersion("3.7.4")
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
          _       <- diagnosticManager.didChange(lspClient, uri.toString, lspDiags)
        } yield ()
      }

    params =>
      for {
        _       <- stateManager.didChange(params)
        _       <- lspClient.logDebug("Updated DocumentState")
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

  sealed trait BuildTool {
  }
  case object Mill extends BuildTool
  case object ScalaCli extends BuildTool

  def detectBuildTool(rootPath: os.Path): IO[BuildTool] = IO {
    // Check for Mill first
    if (os.exists(rootPath / "build.mill") || os.exists(rootPath / "mill")) {
      Mill
    }
    // Check for Scala CLI project indicators
    else if (os.exists(rootPath / "project.scala") ||
             os.exists(rootPath / ".scala-cli") ||
             (os.exists(rootPath) && {
               val files = os.list(rootPath)
               files.exists(file =>
                 file.last.endsWith(".scala") ||
                 file.last.endsWith(".sc") ||
                 file.last.endsWith(".worksheet.sc")
               ) && !files.exists(_.last == "build.mill")
             })) {
      ScalaCli
    }
    else {
      Mill // Default fallback
    }
  }

  def setupBuildTool(rootPath: os.Path, client: SlsLanguageClient[IO]): IO[Unit] = {
    for {
      buildTool <- detectBuildTool(rootPath)
      _ <- client.logMessage(s"Detected build tool: $buildTool")
      _ <- buildTool match {
        case Mill =>
          client.logMessage("Setting up Mill BSP with Bloop...") *>
          importMillBsp(rootPath, client)
        case ScalaCli =>
          client.logMessage("Setting up Scala CLI BSP...") *>
          setupScalaCliBsp(rootPath, client)
      }
      _ <- client.logMessage("Build tool setup completed successfully")
    } yield ()
  }.handleErrorWith { error =>
    client.logMessage(s"Build tool setup failed: ${error.getMessage}") *>
    IO.raiseError(error)
  }

  def importMillBsp(rootPath: os.Path, client: SlsLanguageClient[IO]) = {
    val millExec = "./mill" // TODO if mising then findMillExec()
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

  def setupScalaCliBsp(rootPath: os.Path, client: SlsLanguageClient[IO]): IO[Unit] = {
    def checkScalaCliAvailable: IO[Boolean] =
      ProcessBuilder("scala-cli", "--version")
        .spawn[IO]
        .use(_.exitValue)
        .map(_ == 0)
        .handleErrorWith(_ => IO.pure(false))

    for {
      isAvailable <- checkScalaCliAvailable
      _ <- if (!isAvailable) {
        client.logMessage("Warning: scala-cli not found in PATH. Please install scala-cli to use with Scala CLI projects.") *>
        IO.raiseError(new RuntimeException("scala-cli not available"))
      } else {
        client.logMessage("Found scala-cli, setting up BSP configuration...")
      }
      _ <- ProcessBuilder("scala-cli", "setup-ide", ".")
        .withWorkingDirectory(fs2.io.file.Path.fromNioPath(rootPath.toNIO))
        .spawn[IO]
        .use { process =>
          val allOutput = process.stdout
            .merge(process.stderr)
            .through(text.utf8.decode)
            .through(text.lines)

          allOutput
            .evalMap(line => client.logMessage(s"[scala-cli setup-ide] $line"))
            .compile
            .drain *>
          process.exitValue.flatMap { exitCode =>
            if (exitCode == 0) {
              client.logMessage("Scala CLI BSP configuration generated successfully")
            } else {
              client.logMessage(s"Scala CLI setup-ide exited with code: $exitCode") *>
              IO.raiseError(new RuntimeException(s"scala-cli setup-ide failed with exit code: $exitCode"))
            }
          }
        }
    } yield ()
  }

  private def connectWithScalaCli(
      rootPath: os.Path,
      steward: ResourceSupervisor[IO],
      diagnosticManager: DiagnosticManager,
  ): IO[BuildServer] = {
    import org.scala.abusers.pc.PresentationCompilerDTOInterop.gson
    import java.util.{List as JList}
    import scala.jdk.CollectionConverters.*
    import jsonrpclib.fs2.catsMonadic

    case class ScalaCliBspConfig(
        name: String,
        argv: JList[String], // Use Java List for Gson compatibility
        version: String,
        bspVersion: String,
        languages: JList[String]
    )

    def readBspConfig: IO[ScalaCliBspConfig] = {
      val bspConfigPath = rootPath / ".bsp" / "scala-cli.json"
      for {
        exists <- IO(os.exists(bspConfigPath))
        _ <- if (!exists) {
          IO.raiseError(new RuntimeException(s"BSP config not found at $bspConfigPath"))
        } else IO.unit
        configContent <- IO(os.read(bspConfigPath))
        config <- IO(
          try {
            gson.fromJson(configContent, classOf[ScalaCliBspConfig])
          } catch {
            case e: Exception =>
              throw new RuntimeException(s"Failed to parse BSP config: ${e.getMessage}")
          }
        )
      } yield config
    }

    def makeBspClientFromProcess(process: fs2.io.process.Process[IO], channel: FS2Channel[IO], report: String => IO[Unit]): Resource[IO, BuildServer] =
      fs2.Stream
        .eval(IO.never)
        .concurrently(
          process.stdout.through(jsonrpclib.fs2.lsp.decodeMessages).evalTap(m => report(m.toString)).through(channel.inputOrBounce)
        )
        .concurrently(channel.output.through(jsonrpclib.fs2.lsp.encodeMessages).through(process.stdin))
        .compile
        .drain
        .guarantee(lspClient.logMessage("Scala CLI BSP process terminated"))
        .background
        .as(
          BuildServer(
            smithy4sbsp.bsp4s.BSPCodecs.clientStub(bsp.BuildServer, channel).toTry.get,
            smithy4sbsp.bsp4s.BSPCodecs.clientStub(bsp.jvm.JvmBuildServer, channel).toTry.get,
            smithy4sbsp.bsp4s.BSPCodecs.clientStub(bsp.scala_.ScalaBuildServer, channel).toTry.get,
            smithy4sbsp.bsp4s.BSPCodecs.clientStub(bsp.java_.JavaBuildServer, channel).toTry.get,
          )
        )

    val bspClientRes = for {
      config <- Resource.eval(readBspConfig)
      _ <- Resource.eval(lspClient.logMessage(s"Starting Scala CLI BSP server with config: ${config.name} v${config.version}"))
      argvSeq = config.argv.asScala.toList
      bspProcess <- ProcessBuilder(argvSeq.head, argvSeq.tail*).spawn[IO]
      _ <- Resource.eval(IO.sleep(1.seconds) *> lspClient.logMessage("Scala CLI BSP process spawned"))
      channel <- FS2Channel
        .resource[IO]()
        .flatMap(_.withEndpoints(bspClientHandler(lspClient, diagnosticManager)))
      client <- makeBspClientFromProcess(bspProcess, channel, msg => lspClient.logDebug(s"scala-cli bsp raw: $msg"))
    } yield client

    steward.acquire(bspClientRes)
  }
}
