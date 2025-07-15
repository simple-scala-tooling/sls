package org.scala.abusers.sls

import cats.effect.*
import jsonrpclib.fs2.catsMonadic
import jsonrpclib.fs2.FS2Channel
import jsonrpclib.smithy4sinterop.ClientStub
import org.scala.abusers.pc.IOCancelTokens
import org.scala.abusers.pc.PresentationCompilerProvider

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
// object SimpleScalaServerApp extends IOApp.Simple {
//   val server = for {
//     steward           <- ResourceSupervisor[IO]
//     pcProvider        <- PresentationCompilerProvider.instance.toResource
//     textDocumentSync  <- TextDocumentSyncManager.instance.toResource
//     bspClientDeferred <- Deferred[IO, BuildServer].toResource
//     bspStateManager   <- BspStateManager.instance(BuildServer.suspend(bspClientDeferred.get)).toResource
//     stateManager      <- StateManager.instance(textDocumentSync, bspStateManager).toResource
//     cancelTokens      <- IOCancelTokens.instance
//     diagnosticManager <- DiagnosticManager.instance.toResource
//   } yield ServerImpl(stateManager, pcProvider, cancelTokens, diagnosticManager, steward, bspClientDeferred)

// }

object SimpleScalaServer extends IOApp.Simple {
  import jsonrpclib.smithy4sinterop.ServerEndpoints

  def run: IO[Unit] =
    fs2.Stream
      .resource(server)
      .flatMap { impl =>
        stream(impl)
      }
      .compile
      .drain
      .as(ExitCode.Success)

  def stream(impl: ServerImpl) =
    for {
      fs2Channel <- FS2Channel.stream[IO](cancelTemplate = None)
      client = ClientStub(SlsLanguageClient, fs2Channel).toTry.get
      channelWithEndpoints <- fs2Channel.withEndpointsStream(ServerEndpoints(impl).toTry.get)
      _                    <- fs2.Stream.eval(impl.client.complete(client).void)
      res <- fs2.Stream
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

  def server: Resource[IO, ServerImpl] =
    for {
      steward           <- ResourceSupervisor[IO]
      pcProvider        <- PresentationCompilerProvider.instance.toResource
      textDocumentSync  <- TextDocumentSyncManager.instance.toResource
      bspClientDeferred <- Deferred[IO, BuildServer].toResource
      bspStateManager   <- BspStateManager.instance(BuildServer.suspend(bspClientDeferred.get)).toResource
      stateManager      <- StateManager.instance(textDocumentSync, bspStateManager).toResource
      cancelTokens      <- IOCancelTokens.instance
      diagnosticManager <- DiagnosticManager.instance.toResource
    } yield ServerImpl(stateManager, pcProvider, cancelTokens, diagnosticManager, steward, bspClientDeferred)
}
