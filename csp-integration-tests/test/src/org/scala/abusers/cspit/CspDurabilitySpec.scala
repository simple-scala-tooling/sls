package org.scala.abusers.cspit

import cats.effect.kernel.Resource
import cats.effect.IO
import cats.syntax.all.*
import org.scala.abusers.csp
import weaver.*

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
}
