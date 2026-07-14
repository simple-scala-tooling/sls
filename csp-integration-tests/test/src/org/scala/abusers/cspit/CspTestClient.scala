package org.scala.abusers.cspit

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.effect.Ref
import jsonrpclib.fs2.*
import jsonrpclib.smithy4sinterop.ClientStub
import jsonrpclib.smithy4sinterop.ServerEndpoints
import org.scala.abusers.csp

import scala.concurrent.duration.*

/** Drives the real zincCli assembly as a subprocess over the CSP wire (`ai/test-plan.md` H2). Uses a raw
  * `java.lang.ProcessBuilder` rather than fs2's `Processes` so crash tests (Z5) can `destroyForcibly` mid-compile.
  */
object CspTestClient {

  final case class Handle(
      server: csp.CspServer[IO],
      published: Ref[IO, List[csp.PublishDiagnosticsParams]],
      kill: IO[Unit],
  ) {

    /** Latest published diagnostics for `file` (empty publish means cleared). */
    def diagnosticsFor(file: os.Path): IO[List[csp.CompileDiagnostic]] =
      published.get.map(_.find(_.file == file.toString).map(_.diagnostics).getOrElse(Nil))

    /** Compile `sourceDir` against the forked-in test classpath and Scala version. */
    def compile(scopeId: String, sourceDir: os.Path): IO[csp.CompileOutput] =
      server.compile(
        scopeId = scopeId,
        classpath = testClasspath,
        sourcePath = List(sourceDir.toString),
        scalacOptions = Nil,
        javacOptions = Nil,
        scalaVersion = csp.ScalaVersion(testScalaVersion),
      )
  }

  def serverJar: os.Path = os.Path(requireEnv("SERVER_JAR"))

  def testClasspath: List[String] = requireEnv("TEST_CLASSPATH").split(";").toList.filter(_.nonEmpty)

  def testScalaVersion: String = requireEnv("TEST_SCALA_VERSION")

  private def requireEnv(name: String): String =
    sys.env.getOrElse(name, sys.error(s"$name missing from environment — run via ./mill csp-integration-tests.test"))

  private class CapturingClient(ref: Ref[IO, List[csp.PublishDiagnosticsParams]]) extends csp.CspClient[IO] {
    // newest first, so diagnosticsFor picks up the latest publish per file
    def onPublishDiagnostics(params: csp.PublishDiagnosticsParams): IO[Unit] = ref.update(params :: _)
  }

  def spawn: Resource[IO, Handle] = spawnIn(workDir = None)

  /** Spawn with a stable `--work-dir` so the analysis store and output jars survive across server restarts (Z4+). */
  def spawnIn(workDir: Option[os.Path]): Resource[IO, Handle] =
    for {
      published <- Resource.eval(Ref.of[IO, List[csp.PublishDiagnosticsParams]](Nil))
      command = List("java", "-jar", serverJar.toString) ++ workDir.toList.flatMap(d => List("--work-dir", d.toString))
      process <- Resource.make(
        IO.blocking(new ProcessBuilder(command*).start())
      )(p => IO.blocking { p.destroyForcibly(); p.waitFor() }.void)
      channel <- FS2Channel.resource[IO](cancelTemplate = None)
      _       <- channel.withEndpoints(
        ServerEndpoints(new CapturingClient(published)).toOption
          .getOrElse(sys.error("Couldn't create ServerEndpoints"))
      )
      _ <- fs2.Stream
        .never[IO]
        .concurrently(
          channel.output
            .through(lsp.encodeMessages)
            .through(fs2.io.writeOutputStream(IO.blocking(process.getOutputStream)))
        )
        .concurrently(
          fs2.io
            .readInputStream(IO.blocking(process.getInputStream), 4096)
            .through(lsp.decodeMessages)
            .through(channel.inputOrBounce)
        )
        .concurrently(
          fs2.io.readInputStream(IO.blocking(process.getErrorStream), 4096).through(fs2.io.stderr[IO])
        )
        .compile
        .drain
        .attempt // stream dies when the process is killed — that's fine, kill tests expect it
        .background
      // Registered *after* the pipes so it finalizes *before* them (LIFO): the reader fibers sit in
      // uninterruptible FileInputStream.read calls, and canceling them blocks until the process dies —
      // so the process must die first or teardown deadlocks until the read happens to return.
      _ <- Resource.onFinalize(IO.blocking(process.destroyForcibly()).void)
      server = ClientStub(csp.CspServer, channel).toOption.getOrElse(sys.error("Couldn't create ClientStub"))
    } yield Handle(server, published, IO.blocking(process.destroyForcibly()).void)

  /** Poll `check` until it yields, failing after `timeout`. Notifications arrive concurrently with responses, so
    * assertions on `published` must await, never read once.
    */
  def awaitUntil[A](
      check: IO[Option[A]],
      timeout: FiniteDuration = 30.seconds,
      interval: FiniteDuration = 50.millis,
      label: String = "condition",
  ): IO[A] = {
    def loop(deadline: FiniteDuration): IO[A] =
      check.flatMap {
        case Some(a) => IO.pure(a)
        case None    =>
          IO.monotonic.flatMap { now =>
            if now >= deadline then IO.raiseError(new RuntimeException(s"awaitUntil timed out waiting for $label"))
            else IO.sleep(interval) *> loop(deadline)
          }
      }
    IO.monotonic.flatMap(now => loop(now + timeout))
  }
}

/** A temp directory of sources fed to the compile server; removed on release. */
final case class CspWorkspace(root: os.Path) {

  def write(name: String, content: String): IO[os.Path] =
    IO.blocking {
      val path = root / os.SubPath(name)
      os.write.over(path, content, createFolders = true)
      path
    }

  def delete(name: String): IO[Unit] = IO.blocking(os.remove(root / os.SubPath(name)))

  def path(name: String): os.Path = root / os.SubPath(name)
}

object CspWorkspace {
  def resource: Resource[IO, CspWorkspace] =
    Resource
      .make(IO.blocking(os.temp.dir(prefix = "csp-it-")))(dir => IO.blocking(os.remove.all(dir)))
      .map(CspWorkspace(_))
}
