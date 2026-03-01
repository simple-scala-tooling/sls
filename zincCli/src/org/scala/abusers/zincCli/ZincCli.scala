package org.scala.abusers.zincCli

import cats.effect.*
import cats.syntax.all.*
import jsonrpclib.fs2.*
import jsonrpclib.fs2.FS2Channel
import jsonrpclib.smithy4sinterop.ServerEndpoints
import java.{util => ju}

object ZincCli extends IOApp {

  override protected def reportFailure(err: Throwable): IO[Unit] = {
    IO.consoleForIO.errorln(s"Fatal error in Zinc CLI: ${err.getMessage}, ${err.getStackTrace().mkString("\n")}")
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val app = for {
      fs2Channel           <- FS2Channel.resource[IO](cancelTemplate = None)
      server               <- IO(ZincCliServer()).toResource
      serverEndpoints      <- ServerEndpoints(server).liftTo[IO].toResource
      channelWithEndpoints <- fs2Channel.withEndpoints(serverEndpoints)
      _ <- fs2.Stream
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
        .onError { case e => fs2.Stream.eval(IO.consoleForIO.errorln(s"Error in Zinc CLI main loop: ${e.getMessage}")) }
        .compile
        .drain
        .toResource
    } yield ()

    app.useForever.as(ExitCode.Success)
  }
}
