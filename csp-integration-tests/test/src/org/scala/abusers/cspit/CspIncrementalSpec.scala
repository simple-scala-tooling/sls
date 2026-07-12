package org.scala.abusers.cspit

import cats.syntax.all.*
import org.scala.abusers.csp
import weaver.*

/** Tier-Z convergence (`ai/test-plan.md` Z1/Z3): incremental output must equal a clean compile of the final sources —
  * otherwise "delete the analysis store and rebuild" would fix something, which is the bug.
  */
object CspIncrementalSpec extends SimpleIOSuite {

  test("editing B's API recompiles the dependents closure and matches a clean compile (test-plan Z1)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        use <- ws.write(
          "Use.scala",
          """package inc
            |
            |class Use {
            |  val n: Long = Lib.value
            |}
            |""".stripMargin,
        )
        lib <- ws.write(
          "Lib.scala",
          """package inc
            |
            |object Lib {
            |  def value: Int = 1
            |}
            |""".stripMargin,
        )
        r1 <- h.compile("z1", ws.root)
        // API change that both versions of Use.scala compile against: Int -> Long return type.
        _ <- ws.write(
          "Lib.scala",
          """package inc
            |
            |object Lib {
            |  def value: Long = 2L
            |}
            |""".stripMargin,
        )
        r2      <- h.compile("z1", ws.root)
        entries <- CompileOracle.jarEntries(os.Path(r2.outputJar))
        oracle  <- CompileOracle.cleanOutputOf(ws.root)
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, r1.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r2.status) &&
        // the closure: the un-edited dependent must be reported, not just the touched file
        expect.same(Set(lib.toString, use.toString), r2.changedFiles.keySet) &&
        // Entry names only: betasty bytes differ between an incremental cycle and a clean joint compile
        // (different TASTy UUID and tree sizes) — whether best-effort pickling should be grouping-deterministic
        // is an open question; content-hash convergence is blocked on it.
        expect.same(oracle.keySet, entries.keySet)
    }
  }

  test("deleting a source removes its entries from the output jar (test-plan Z3)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        _ <- ws.write(
          "Keep.scala",
          """package inc
            |
            |class Keep {
            |  def k: Int = 1
            |}
            |""".stripMargin,
        )
        gone <- ws.write(
          "Gone.scala",
          """package inc
            |
            |class Gone {
            |  def g: Int = 2
            |}
            |""".stripMargin,
        )
        r1 <- h.compile("z3", ws.root)
        e1 <- CompileOracle.jarEntries(os.Path(r1.outputJar))
        _  <- ws.delete("Gone.scala")
        r2      <- h.compile("z3", ws.root)
        entries <- CompileOracle.jarEntries(os.Path(r2.outputJar))
        oracle  <- CompileOracle.cleanOutputOf(ws.root)
      } yield expect(e1.contains("inc/Gone.betasty"), s"precondition: Gone.betasty in first jar, got ${e1.keySet}") &&
        expect(!entries.contains("inc/Gone.betasty"), s"ghost entry for the deleted source: ${entries.keySet}") &&
        expect(
          r2.changedFiles.get(gone.toString).contains(Nil),
          s"deleted source not reported with empty entries: ${r2.changedFiles}",
        ) &&
        expect.same(oracle, entries)
    }
  }
}
