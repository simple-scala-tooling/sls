package org.scala.abusers.sls

import cats.effect.*
import cats.syntax.all.*
import fs2.io.process.Process
import jsonrpclib.fs2.{lsp => jsonrpclibLsp, *}
import jsonrpclib.smithy4sinterop.ClientStub
import org.scala.abusers.csp.CspClient
import org.scala.abusers.csp.CspServer

class ZincCspClient extends CspClient[IO] {}

object ZincCspClient {
  private val cspStderrLogger = org.slf4j.LoggerFactory.getLogger("org.scala.abusers.sls.CspStderr")

  def makeCspClient(
      process: Process[IO],
      channel: FS2Channel[IO],
      report: String => IO[Unit],
  ): Resource[IO, CspServer[IO]] =
    fs2.Stream
      .eval(IO.never)
      .concurrently(
        channel.output
          .through(CspMessageTracer.traceOutgoing)
          .through(jsonrpclibLsp.encodeMessages)
          .through(process.stdin)
      )
      .concurrently(
        process.stdout
          .through(jsonrpclibLsp.decodeMessages)
          .through(CspMessageTracer.traceIncoming)
          .through(channel.inputOrBounce)
      )
      .concurrently(
        process.stderr
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .evalMap(line => IO(cspStderrLogger.warn(line)))
      )
      .compile
      .drain
      .handleErrorWith { e =>
        IO(cspStderrLogger.error("CSP server stream failed", e))
      }
      .guarantee(IO(cspStderrLogger.warn("Terminating csp server")))
      .background
      .as(
        ClientStub(CspServer, channel).toOption.getOrElse(
          sys.error("Couldn't create ClientStub")
        )
      )
}
