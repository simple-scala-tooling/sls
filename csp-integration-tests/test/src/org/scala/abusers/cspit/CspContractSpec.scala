package org.scala.abusers.cspit

import cats.syntax.all.*
import org.scala.abusers.csp
import weaver.*

import CspTestClient.awaitUntil

/** The executable spec of the CSP compile contract (`ai/test-plan.md` Z2/Z8): diagnostics fidelity against a
  * clean-compile oracle, and the response shape per [[csp.CompilationStatus]].
  *
  * The BEST_EFFORT arm is missing on purpose: zinc throws `CompileFailed` before the best-effort output is moved into
  * place, so the status is unreachable until H6 (salvage after CompileFailed) lands.
  */
object CspContractSpec extends SimpleIOSuite {

  private val goodLib =
    """package inc
      |
      |object Lib {
      |  def value: Int = 1
      |}
      |""".stripMargin

  private val brokenLib =
    """package inc
      |
      |object Lib {
      |  def value: Int = "boom"
      |}
      |""".stripMargin

  test("error flip: diagnostics match a fresh compile of the same broken sources, then clear (test-plan Z2)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        lib <- ws.write("Lib.scala", goodLib)
        r1  <- h.compile("z2", ws.root)

        _     <- ws.write("Lib.scala", brokenLib)
        r2    <- h.compile("z2", ws.root)
        diags <- awaitUntil(
          h.diagnosticsFor(lib).map(d => Option.when(d.nonEmpty)(d)),
          label = "diagnostics from the incremental erroring compile",
        )
        // Oracle: a fresh server compiling the same broken sources from scratch must report the same diagnostics —
        // incremental state must not change what the user is told.
        oracleDiags <- CspTestClient.spawn.use { oracle =>
          oracle.compile("z2-oracle", ws.root) *>
            awaitUntil(
              oracle.diagnosticsFor(lib).map(d => Option.when(d.nonEmpty)(d)),
              label = "diagnostics from the clean-compile oracle",
            )
        }

        _  <- ws.write("Lib.scala", goodLib)
        r3 <- h.compile("z2", ws.root)
        _  <- awaitUntil(
          h.diagnosticsFor(lib).map(d => Option.when(d.isEmpty)(())),
          label = "diagnostics to clear after the fix",
        )
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, r1.status) &&
        expect.same(csp.CompilationStatus.FAILED, r2.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r3.status) &&
        expect.same(oracleDiags.toSet, diags.toSet)
    }
  }

  test("response shape per status: FAILED carries no changedFiles, SUCCEEDED carries the output (test-plan Z8)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        lib <- ws.write("Lib.scala", brokenLib)
        // First-ever compile of the scope fails: nothing usable may be advertised.
        failed <- h.compile("z8", ws.root)
        _      <- awaitUntil(
          h.diagnosticsFor(lib).map(d => Option.when(d.nonEmpty)(d)),
          label = "diagnostics accompanying the FAILED response",
        )

        _         <- ws.write("Lib.scala", goodLib)
        succeeded <- h.compile("z8", ws.root)
        entries   <- CompileOracle.jarEntries(os.Path(succeeded.outputJar))
      } yield expect.same(csp.CompilationStatus.FAILED, failed.status) &&
        expect(failed.changedFiles.isEmpty, s"FAILED must not advertise changedFiles: ${failed.changedFiles}") &&
        expect.same(csp.CompilationStatus.SUCCEEDED, succeeded.status) &&
        expect(
          succeeded.changedFiles.keySet == Set(lib.toString),
          s"SUCCEEDED changedFiles should cover the compiled source, got ${succeeded.changedFiles.keySet}",
        ) &&
        expect(entries.keys.exists(_.startsWith("inc/Lib")), s"output jar missing Lib entries: ${entries.keySet}")
    }
  }
}
