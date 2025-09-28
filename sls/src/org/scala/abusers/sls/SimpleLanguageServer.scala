package org.scala.abusers.sls

import cats.effect.*
import cats.syntax.all.*
import jsonrpclib.fs2.*
import jsonrpclib.smithy4sinterop.ClientStub
import jsonrpclib.CallId
import org.scala.abusers.pc.IOCancelTokens
import org.scala.abusers.pc.PresentationCompilerProvider
import org.scala.abusers.profiling.runtime.ProfilingIOApp
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

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

private case class LSPCancelRequest(id: CallId)

object LSPCancelRequest {
  import io.circe.Codec
  given Codec[LSPCancelRequest] = Codec.derived[LSPCancelRequest]

  val cancelTemplate: CancelTemplate = CancelTemplate
    .make[LSPCancelRequest](
      "$/cancelRequest",
      _.id,
      LSPCancelRequest(_),
    )
}

object SimpleScalaServer extends ProfilingIOApp {
  import jsonrpclib.smithy4sinterop.ServerEndpoints

  override def applicationName: String = "simple-language-server"

  override def program(using meter: Meter[IO], tracer: Tracer[IO]) =
    for {
      fs2Channel           <- FS2Channel.resource[IO](cancelTemplate = LSPCancelRequest.cancelTemplate.some)
      client               <- ClientStub(SlsLanguageClient, fs2Channel).liftTo[IO].toResource
      serverImpl           <- server(client)
      serverEndpoints      <- ServerEndpoints(serverImpl).liftTo[IO].toResource
      channelWithEndpoints <- fs2Channel.withEndpoints(serverEndpoints)
      _ <- fs2.Stream // Refactor to be single threaded
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
        .compile
        .drain
        .toResource
    } yield ()

  private def server(lspClient: SlsLanguageClient[IO])(using Tracer[IO], Meter[IO]): Resource[IO, ServerImpl] =
    for {
      steward           <- ResourceSupervisor[IO]
      pcProvider        <- PresentationCompilerProvider.instance.toResource
      textDocumentSync  <- TextDocumentSyncManager.instance.toResource
      bspClientDeferred <- Deferred[IO, BuildServer].toResource
      bspStateManager   <- BspStateManager.instance(lspClient, BuildServer.suspend(bspClientDeferred.get)).toResource
      cancelTokens      <- IOCancelTokens.instance
      diagnosticManager <- DiagnosticManager.instance.toResource
      computationQueue  <- ComputationQueue.instance.toResource
    } yield ServerImpl(
      pcProvider,
      cancelTokens,
      diagnosticManager,
      steward,
      bspClientDeferred,
      lspClient,
      computationQueue,
      textDocumentSync,
      bspStateManager,
    )
}
