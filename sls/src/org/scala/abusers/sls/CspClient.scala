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
import fs2.io.process.Process
import jsonrpclib.smithy4sinterop.ServerEndpoints
import org.scala.abusers.csp.CspClient
import org.scala.abusers.csp.CspServer
import jsonrpclib.smithy4sinterop.ClientStub
import org.scala.abusers.csp.CspServerGen
import fs2.io.file.Flags
import java.nio.file.Files
import java.nio.file.Path

class ZincCspClient extends CspClient[IO] { }

object ZincCspClient {
  def makeCspClient(process: Process[IO], channel: FS2Channel[IO], report: String => IO[Unit]): Resource[IO, CspServer[IO]] = {
    fs2.Stream
      .eval(IO.never)
      .concurrently(
        channel.output
          .through(jsonrpclibLsp.encodeMessages)
          .through(process.stdin)
      )
      .concurrently(
        process.stdout
          .through(jsonrpclibLsp.decodeMessages)
          .through(channel.inputOrBounce)
      )
      .concurrently(process.stderr.through(fs2.io.file.Files[IO].writeAll(fs2.io.file.Path("csp-server-stderr.log"), Flags.Append)))
      .compile
      .drain
      .guarantee(IO.consoleForIO.errorln("Terminating csp server"))
      .background
      .as(
        ClientStub(CspServer, channel).toOption.getOrElse(
          sys.error("Couldn't create ClientStub")
        )
      )
  }
}
