package org.scala.abusers.cspit

import cats.syntax.all.*
import org.scala.abusers.csp
import weaver.*

import CspTestClient.awaitUntil

/** H2 smoke tests: the harness itself works against the real zincCli subprocess — compile round-trip, jar inspection,
  * and the diagnostics notification path. Tier-Z scenarios build on this.
  */
object CspSmokeSpec extends SimpleIOSuite {

  test("clean compile produces a jar with entries for every source (smoke)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        _ <- ws.write(
          "Foo.scala",
          """package smoke
            |
            |class Foo {
            |  def value: Int = 42
            |}
            |""".stripMargin,
        )
        _ <- ws.write(
          "Bar.scala",
          """package smoke
            |
            |class Bar(foo: Foo) {
            |  def doubled: Int = foo.value * 2
            |}
            |""".stripMargin,
        )
        result  <- h.compile("smoke-clean", ws.root)
        entries <- CompileOracle.jarEntries(os.Path(result.outputJar))
      } yield expect.same(csp.CompilationStatus.SUCCEEDED, result.status) &&
        // zincCli's contract is best-effort TASTy output: the jar carries .betasty entries, not classfiles
        expect.same(csp.OutputFormat.BETASTY, result.outputFormat) &&
        expect(entries.contains("smoke/Foo.betasty"), s"Foo.betasty missing from ${entries.keySet}") &&
        expect(entries.contains("smoke/Bar.betasty"), s"Bar.betasty missing from ${entries.keySet}") &&
        expect(
          result.changedFiles.keySet == Set(ws.path("Foo.scala").toString, ws.path("Bar.scala").toString),
          s"changedFiles should cover both new sources, got ${result.changedFiles.keySet}",
        )
    }
  }

  test("broken compile publishes ERROR diagnostics; the fixed recompile clears them (smoke)") {
    (CspTestClient.spawn, CspWorkspace.resource).tupled.use { case (h, ws) =>
      for {
        broken <- ws.write(
          "Broken.scala",
          """package smoke
            |
            |object Broken {
            |  val x: Int = "not an int"
            |}
            |""".stripMargin,
        )
        result <- h.compile("smoke-broken", ws.root)
        diags  <- awaitUntil(
          h.diagnosticsFor(broken).map(d => Option.when(d.nonEmpty)(d)),
          label = "diagnostics for the broken file",
        )

        _      <- ws.write(
          "Broken.scala",
          """package smoke
            |
            |object Broken {
            |  val x: Int = 42
            |}
            |""".stripMargin,
        )
        fixed <- h.compile("smoke-broken", ws.root)
        _     <- awaitUntil(
          h.diagnosticsFor(broken).map(d => Option.when(d.isEmpty)(())),
          label = "the empty publish clearing the broken file",
        )
      } yield expect(result.status != csp.CompilationStatus.SUCCEEDED, s"broken compile reported ${result.status}") &&
        expect(
          diags.exists(_.severity == csp.DiagnosticSeverity.ERROR),
          s"expected an ERROR diagnostic, got $diags",
        ) &&
        expect(
          diags.exists(_.range.exists(_.startLine == 3)),
          s"diagnostic should anchor to 0-based line 3, got ${diags.map(_.range)}",
        ) &&
        expect.same(csp.CompilationStatus.SUCCEEDED, fixed.status)
    }
  }
}
