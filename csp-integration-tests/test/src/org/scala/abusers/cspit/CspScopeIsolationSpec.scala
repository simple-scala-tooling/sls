package org.scala.abusers.cspit

import cats.syntax.all.*
import org.scala.abusers.csp
import weaver.*

/** Scope isolation (`ai/test-plan.md` Z7): same-scope compiles must serialize onto one analysis store without
  * corrupting it; distinct scopes must not leak into each other's outputs. No timing assertions — overlap is not
  * observable deterministically, so the assertions are about state, not schedules.
  */
object CspScopeIsolationSpec extends SimpleIOSuite {

  test("concurrent same-scope compiles leave a valid store behind (test-plan Z7)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        a <- ws.write(
          "A.scala",
          """package inc
            |
            |class A {
            |  def a: Int = 1
            |}
            |""".stripMargin,
        )
        _ <- ws.write(
          "B.scala",
          """package inc
            |
            |class B {
            |  def b: Int = 2
            |}
            |""".stripMargin,
        )
        results <- (h.compile("z7", ws.root), h.compile("z7", ws.root)).parTupled
        (r1, r2) = results
        // the store survived the concurrency: a follow-up sees exactly the edit, not a rebuild
        _  <- ws.write(
          "A.scala",
          """package inc
            |
            |class A {
            |  def a: Int = 42
            |}
            |""".stripMargin,
        )
        r3 <- h.compile("z7", ws.root)
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, r1.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r2.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, r3.status) &&
        expect.same(Set(a.toString), r3.changedFiles.keySet)
    }
  }

  test("distinct scopes do not cross-contaminate outputs (test-plan Z7)") {
    (CspTestClient.spawn, CspWorkspace.resource, CspWorkspace.resource).tupled.use { case (h, wsA, wsB) =>
      for {
        _ <- wsA.write(
          "A.scala",
          """package pkga
            |
            |class A {
            |  def a: Int = 1
            |}
            |""".stripMargin,
        )
        _ <- wsB.write(
          "B.scala",
          """package pkgb
            |
            |class B {
            |  def b: Int = 2
            |}
            |""".stripMargin,
        )
        results <- (h.compile("z7a", wsA.root), h.compile("z7b", wsB.root)).parTupled
        (ra, rb) = results
        ea <- CompileOracle.jarEntries(os.Path(ra.outputJar))
        eb <- CompileOracle.jarEntries(os.Path(rb.outputJar))
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, ra.status) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, rb.status) &&
        expect(ea.keys.exists(_.startsWith("pkga/")), s"scope A's own entries missing: ${ea.keySet}") &&
        expect(eb.keys.exists(_.startsWith("pkgb/")), s"scope B's own entries missing: ${eb.keySet}") &&
        expect(!ea.keys.exists(_.startsWith("pkgb/")), s"scope B leaked into A's jar: ${ea.keySet}") &&
        expect(!eb.keys.exists(_.startsWith("pkga/")), s"scope A leaked into B's jar: ${eb.keySet}")
    }
  }
}
