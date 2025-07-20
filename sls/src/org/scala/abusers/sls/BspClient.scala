package org.scala.abusers.sls

import bsp.BuildClient
import bsp.DidChangeBuildTarget
import bsp.LogMessageParams
import bsp.OnBuildTaskFinishInput
import bsp.OnBuildTaskStartInput
import bsp.PrintParams
import bsp.PublishDiagnosticsParams
import bsp.ShowMessageParams
import bsp.TaskProgressParams
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.*
import fs2.io.net.Network
import jsonrpclib.fs2.{lsp => jsonrpclibLsp, *}
import jsonrpclib.Endpoint
import smithy4sbsp.bsp4s.BSPCodecs

def makeBspClient(path: String, channel: FS2Channel[IO], report: String => IO[Unit]): Resource[IO, BuildServer] =
  Network[IO]
    .connect(UnixSocketAddress(path))
    .flatMap { socket =>
      fs2.Stream
        .eval(IO.never)
        .concurrently(
          socket.reads
            .through(jsonrpclibLsp.decodeMessages)
            .evalTap(m => report(m.toString))
            .through(channel.inputOrBounce)
        )
        .concurrently(channel.output.through(jsonrpclibLsp.encodeMessages).through(socket.writes))
        .compile
        .drain
        .guarantee(IO.consoleForIO.errorln("Terminating server"))
        .background
    }
    .as(
      BuildServer(
        BSPCodecs.clientStub(bsp.BuildServer, channel).toTry.get,
        BSPCodecs.clientStub(bsp.jvm.JvmBuildServer, channel).toTry.get,
        BSPCodecs.clientStub(bsp.scala_.ScalaBuildServer, channel).toTry.get,
        BSPCodecs.clientStub(bsp.java_.JavaBuildServer, channel).toTry.get,
      )
    )

def bspClientHandler(lspClient: SlsLanguageClient[IO], diagnosticManager: DiagnosticManager): List[Endpoint[IO]] =
  BSPCodecs
    .serverEndpoints(
      new BuildClient[IO] {

        

        def onBuildLogMessage(input: LogMessageParams): IO[Unit] = IO.unit // we want some logging to file here

        def onBuildPublishDiagnostics(input: PublishDiagnosticsParams): IO[Unit] =
          // notify(s"We've just got $input") >>
          diagnosticManager.onBuildPublishDiagnostics(lspClient, input)

        def onBuildShowMessage(input: ShowMessageParams): IO[Unit] = IO.unit

        def onBuildTargetDidChange(input: DidChangeBuildTarget): IO[Unit] = IO.unit

        def onBuildTaskFinish(input: OnBuildTaskFinishInput): IO[Unit] = IO.unit

        def onBuildTaskProgress(input: TaskProgressParams): IO[Unit] = IO.unit

        def onBuildTaskStart(input: OnBuildTaskStartInput): IO[Unit] = IO.unit

        def onRunPrintStderr(input: PrintParams): IO[Unit] = IO.unit

        def onRunPrintStdout(input: PrintParams): IO[Unit] = IO.unit
      }
    )
    .toTry
    .get
