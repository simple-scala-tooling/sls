package org.scala.abusers.example

import cats.effect._
import cats.syntax.all._
import fs2.io.process.Processes
import fs2.Stream
import jsonrpclib.fs2._
import jsonrpclib.smithy4sinterop.ClientStub
import jsonrpclib.smithy4sinterop.ServerEndpoints
import jsonrpclib.CallId
import org.scala.abusers.csp.CspClient
import org.scala.abusers.csp.CspServer
import org.scala.abusers.csp.ScalaVersion
import java.nio.file.Path

object SmithyClientMain extends IOApp.Simple {

  // Reserving a method for cancelation.
  val cancelEndpoint = CancelTemplate.make[CallId]("$/cancel", identity, identity)

  type IOStream[A] = fs2.Stream[IO, A]
  // log in color blue
  def log(str: String): IOStream[Unit] = Stream.eval(IO.consoleForIO.errorln(s"\u001b[34m[client] $str \u001b[0m"))

  // Implementing the generated interface
  object Client extends CspClient[IO] {

  }

  def findFiles(sourcePath: Seq[Path]): IO[Seq[fs2.io.file.Path]] = {
    IO.parSequence {
      sourcePath.map { path =>
        fs2.io.file.Files[IO]
          .walk(fs2.io.file.Path(path.toString))
          .filter(_.extName == ".scala")
          .compile
          .toList
      }
    }.map(_.flatten)
  }

  def withTestWorkspace(f: => IO[?]): IO[Unit] = {

    for {
      sourcePath <- sys.env.get("TEST_SOURCES").liftTo[IO](new Exception("TEST_SOURCES env var does not exists"))
      sources0 <- findFiles(sourcePath.split(";").map(Path.of(_)))
      state <- sources0.parTraverse { path => fs2.io.file.Files[IO].readAll(path).compile.toVector.map(path -> _) }
      _ <- f.guarantee(state.parTraverse_ { case (path, bytes) => fs2.io.file.Files[IO].writeAll(path).apply(Stream.emits(bytes)).compile.drain } )
    } yield ()
  }

  def run: IO[Unit] = withTestWorkspace {
    val run = for {
      ////////////////////////////////////////////////////////
      /////// BOOTSTRAPPING
      ////////////////////////////////////////////////////////
      _         <- log("Starting client")
      serverJar <- sys.env.get("SERVER_JAR").liftTo[IOStream](new Exception("SERVER_JAR env var does not exist"))
      // Starting the server
      rp <- Stream.resource(
        Processes[IO]
          .spawn(
            fs2.io.process.ProcessBuilder(
              "java",
              "-jar",
              serverJar,
            )
          )
      )
      // Creating a channel that will be used to communicate to the server
      fs2Channel <- FS2Channel.stream[IO](cancelTemplate = None)
      // Mounting our implementation of the generated interface onto the channel
      _ <- fs2Channel.withEndpointsStream(
        ServerEndpoints(Client).toOption.getOrElse(sys.error("Couldn't create ServerEndpoints"))
      )
      // Creating stubs to talk to the remote server
      server: CspServer[IO] = ClientStub(CspServer, fs2Channel).toOption.getOrElse(
        sys.error("Couldn't create ClientStub")
      )
      _ <- Stream(())
        .concurrently(
          fs2Channel.output
            // .evalTap(IO.println)
            .through(lsp.encodeMessages)
            // .through(lsp.decodePayloads)
            // .evalTap(IO.println)
            // .through(lsp.encodePayloads)
            .through(rp.stdin)
        )
        .concurrently(
          rp.stdout
            // .through(lsp.decodePayloads)
            // .evalTap(IO.println(_))
            // .through(lsp.encodePayloads)
            .through(lsp.decodeMessages)
            .through(fs2Channel.inputOrBounce)
        )
        .concurrently(rp.stderr.through(fs2.io.stderr[IO]))

      ////////////////////////////////////////////////////////
      /////// INTERACTION
      ////////////////////////////////////////////////////////
      classpath <- sys.env.get("TEST_CLASSPATH").liftTo[IOStream](new Exception("TEST_CLASSPATH env var does not exists"))
      sourcePath <- sys.env.get("TEST_SOURCES").liftTo[IOStream](new Exception("TEST_SOURCES env var does not exists"))
      sources0 <- fs2.Stream.eval(findFiles(sourcePath.split(";").map(Path.of(_))))
      testScalaVersion <- sys.env.get("TEST_SCALA_VERSION").liftTo[IOStream](new Exception("TEST_SCALA_VERSION env var does not exists"))
      result1 <- Stream.eval {
        server.compile(
          scopeId = "testModule", classpath = classpath.split(";").toList, sourcePath = sourcePath.split(";").toList, scalacOptions = List(""), javacOptions = List(""), scalaVersion = ScalaVersion(testScalaVersion)
        )
      }
      _ <- log(s"Client received RESULT 1 $result1")
      _ <- log("\n=================================================================================================\n")
      testObject <- sources0.find(_.toString.contains("TestObject")).liftTo[IOStream](new Exception("Couldn't find TestObject.scala"))
      testObjectContent  <- fs2.io.file.Files[IO].readUtf8(testObject)

      _ <- fs2.Stream.eval(fs2.Stream(testObjectContent.replace("%replaceMe%", "123")).through(fs2.io.file.Files[IO].writeUtf8(testObject)).compile.drain)
      result2 <- Stream.eval {
        server.compile(
          scopeId = "testModule", classpath = classpath.split(";").toList, sourcePath = sourcePath.split(";").toList, scalacOptions = List(""), javacOptions = List(""), scalaVersion = ScalaVersion(testScalaVersion)
        )
      }
      _ <- log(s"Client received RESULT 2 $result2")
      _ <- log("\n=================================================================================================\n")

      _ <- fs2.Stream.eval(fs2.Stream(testObjectContent.replace("%replaceMe%", "\"1234\"")).through(fs2.io.file.Files[IO].writeUtf8(testObject)).compile.drain)
      result3 <- Stream.eval {
        server.compile(
          scopeId = "testModule", classpath = classpath.split(";").toList, sourcePath = sourcePath.split(";").toList, scalacOptions = List(""), javacOptions = List(""), scalaVersion = ScalaVersion(testScalaVersion)
        )
      }
      _ <- log(s"Client received RESULT 3 $result3")
    } yield ()
    run.compile.drain.guarantee(IO.consoleForIO.errorln("Terminating client"))
  }

}
