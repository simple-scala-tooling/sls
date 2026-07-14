package org.scala.abusers.cspit

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.syntax.all.*
import org.scala.abusers.csp
import weaver.*

import scala.concurrent.duration.*

/** Tier-Z durability (`ai/test-plan.md` Z4+): the analysis store must survive a server restart — otherwise every
  * session pays full rebuilds and the store is decorative.
  */
object CspDurabilitySpec extends SimpleIOSuite {

  private val workDirResource: Resource[IO, os.Path] =
    Resource.make(IO.blocking(os.temp.dir(prefix = "csp-it-workdir-")))(dir => IO.blocking(os.remove.all(dir)))

  test("analysis survives a server restart: only the edited file recompiles (test-plan Z4)") {
    (CspWorkspace.resource, workDirResource).tupled.use { case (ws, workDir) =>
      for {
        solo <- ws.write(
          "Solo.scala",
          """package inc
            |
            |class Solo {
            |  def s: Int = 1
            |}
            |""".stripMargin,
        )
        _ <- ws.write(
          "Other.scala",
          """package inc
            |
            |class Other {
            |  def o: Int = 2
            |}
            |""".stripMargin,
        )
        r1 <- CspTestClient.spawnIn(Some(workDir)).use(_.compile("z4", ws.root))

        // Same workDir, fresh server process: the persisted analysis must make this compile incremental.
        _ <- ws.write(
          "Solo.scala",
          """package inc
            |
            |class Solo {
            |  def s: Int = 42
            |}
            |""".stripMargin,
        )
        r2      <- CspTestClient.spawnIn(Some(workDir)).use(_.compile("z4", ws.root))
        entries <- CompileOracle.jarEntries(os.Path(r2.outputJar))
        oracle  <- CompileOracle.cleanOutputOf(ws.root)
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, r1.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r2.status) &&
        // The proof of reuse: a lost analysis would report every source as added, not just the edit.
        expect.same(Set(solo.toString), r2.changedFiles.keySet) &&
        expect.same(oracle.keySet, entries.keySet)
    }
  }

  test("kill -9 mid-compile never wedges the store (test-plan Z5)") {
    (CspWorkspace.resource, workDirResource).tupled.use { case (ws, workDir) =>
      for {
        _ <- (1 to 40).toList.traverse_ { i =>
          ws.write(s"Gen$i.scala", s"package inc\n\nclass Gen$i {\n  def v: Int = $i\n}\n")
        }
        // Chaos injection, not an assertion: the sleep only biases the kill toward mid-compile. The test is
        // valid on either side of the race — killed before, during, or after, the next server must recover.
        _ <- CspTestClient.spawnIn(Some(workDir)).use { h =>
          for {
            fiber <- h.compile("z5", ws.root).start
            _     <- IO.sleep(1500.millis)
            _     <- h.kill
            _     <- fiber.cancel
          } yield ()
        }
        r       <- CspTestClient.spawnIn(Some(workDir)).use(_.compile("z5", ws.root))
        entries <- CompileOracle.jarEntries(os.Path(r.outputJar))
        oracle  <- CompileOracle.cleanOutputOf(ws.root)
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, r.status) &&
        expect.same(oracle.keySet, entries.keySet)
    }
  }

  test("corrupt analysis store falls back to a clean compile and heals (test-plan Z6)") {
    (CspWorkspace.resource, workDirResource).tupled.use { case (ws, workDir) =>
      for {
        keep <- ws.write(
          "Keep.scala",
          """package inc
            |
            |class Keep {
            |  def k: Int = 1
            |}
            |""".stripMargin,
        )
        edit <- ws.write(
          "Edit.scala",
          """package inc
            |
            |class Edit {
            |  def e: Int = 2
            |}
            |""".stripMargin,
        )
        r1 <- CspTestClient.spawnIn(Some(workDir)).use(_.compile("z6", ws.root))
        analysisJar = workDir / "analysis" / "z6-analysis.jar"
        _ <- IO.raiseWhen(!os.exists(analysisJar))(new RuntimeException(s"precondition: no analysis at $analysisJar"))
        _ <- IO.blocking(os.write.over(analysisJar, "definitely not zinc protobuf"))
        results <- CspTestClient.spawnIn(Some(workDir)).use { h =>
          for {
            r2 <- h.compile("z6", ws.root)
            _  <- ws.write(
              "Edit.scala",
              """package inc
                |
                |class Edit {
                |  def e: Int = 42
                |}
                |""".stripMargin,
            )
            r3 <- h.compile("z6", ws.root)
          } yield (r2, r3)
        }
        (r2, r3) = results
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, r1.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r2.status) &&
        // the garbage store degraded to a clean rebuild: every source reported changed
        expect.same(Set(keep.toString, edit.toString), r2.changedFiles.keySet) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r3.status) &&
        // and it healed: the follow-up compile is incremental again
        expect.same(Set(edit.toString), r3.changedFiles.keySet)
    }
  }
}
