package org.scala.abusers.sls

import cats.effect.*
import jsonrpclib.smithy4sinterop.ClientStub
import org.scala.abusers.pc.IOCancelTokens
import org.scala.abusers.pc.PresentationCompilerProvider
import jsonrpclib.fs2.*
import jsonrpclib.CallId
import cats.syntax.all.*

case class BuildServer(
    generic: bsp.BuildServer[IO],
    jvm: bsp.jvm.JvmBuildServer[IO],
    scala: bsp.scala_.ScalaBuildServer[IO],
    java: bsp.java_.JavaBuildServer[IO],
)

object BuildServer {
  def suspend(client: IO[BuildServer]): BuildServer = BuildServer(
    SmithySuspend.sus(client.map(_.generic)),
    SmithySuspend.sus(client.map(_.jvm)),
    SmithySuspend.sus(client.map(_.scala)),
    SmithySuspend.sus(client.map(_.java)),
  )
}

object SimpleScalaServer extends IOApp.Simple {
  import jsonrpclib.smithy4sinterop.ServerEndpoints

  val cancelEndpoint = CancelTemplate.make[CallId]("$/cancel", identity, identity)

  def run: IO[Unit] =
    stream
      .compile
      .drain
      .as(ExitCode.Success)

  def stream =
    for {
      fs2Channel <- FS2Channel.stream[IO](cancelTemplate = cancelEndpoint.some)
      client = ClientStub(SlsLanguageClient, fs2Channel).toTry.get
      serverImpl <- fs2.Stream.resource(server(client))
      channelWithEndpoints <- fs2Channel.withEndpointsStream(ServerEndpoints(serverImpl).toTry.get)
      res <- fs2.Stream // Refactor to be single threaded
        .never[IO]
        .concurrently(
          // STDIN
          fs2.io
            .stdin[IO](512)
            .through(jsonrpclib.fs2.lsp.decodeMessages)
            .through(channelWithEndpoints.inputOrBounce)
        )
        .concurrently(
          // STDOUT
          channelWithEndpoints.output
            .through(jsonrpclib.fs2.lsp.encodeMessages[IO])
            .through(fs2.io.stdout[IO])
        )
    } yield res

  def server(lspClient: SlsLanguageClient[IO]): Resource[IO, ServerImpl] =
    for {
      steward           <- ResourceSupervisor[IO]
      pcProvider        <- PresentationCompilerProvider.instance.toResource
      textDocumentSync  <- TextDocumentSyncManager.instance.toResource
      bspClientDeferred <- Deferred[IO, BuildServer].toResource
      bspStateManager   <- BspStateManager.instance(lspClient, BuildServer.suspend(bspClientDeferred.get)).toResource
      stateManager      <- StateManager.instance(lspClient, textDocumentSync, bspStateManager).toResource
      cancelTokens      <- IOCancelTokens.instance
      diagnosticManager <- DiagnosticManager.instance.toResource
    } yield ServerImpl(stateManager, pcProvider, cancelTokens, diagnosticManager, steward, bspClientDeferred, lspClient)
}
