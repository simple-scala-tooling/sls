package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

/** Verifies that TastyIndexer, BytecodeIndexer, and JavaIndexer emit the same SymbolId
  * for the same source-level symbols in the cross-producer fixture (`crossProducerFixture`).
  *
  * The fixture JAR is pre-compiled and published to `~/.m2` by `sls.test.forkArgs` — see
  * `IndexTestFixtures`. No runtime compilation.
  *
  * All assertions are ignored until Phase 1 fixes the `Cls.foo` vs `Cls#foo` id mismatch.
  * When Phase 1 is complete, delete each `ignore(...)` line and watch the tests go green.
  * The fixture covers: top-level class, companion object, overloaded methods, inner class.
  */
object CrossProducerSpec extends SimpleIOSuite {

  private val pendingReason =
    "pending until Phase 1: TastyIndexer emits crossproducer.Lib.compute, " +
      "BytecodeIndexer emits crossproducer.Lib#compute — canonical SymbolId required"

  private def tastySymbols: IO[List[IndexedSymbol]] =
    TastyIndexer("test").indexJar(IndexTestFixtures.crossProducerJar, Nil).map(_.values.flatMap(_._1).toList)

  private def bytecodeSymbols: IO[List[IndexedSymbol]] =
    BytecodeIndexer().indexJar(IndexTestFixtures.crossProducerJar)

  private def javaSymbols: IO[List[IndexedSymbol]] =
    JavaIndexer
      .forProject("test")
      .indexFiles(List(IndexTestFixtures.crossProducerLibJSrc), Nil)
      .map(_.values.flatMap(_._1).toList)

  // ── Top-level class ──────────────────────────────────────────────────────────

  test("top-level class id agrees between TastyIndexer and BytecodeIndexer") {
    ignore(pendingReason) *> {
      for {
        tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
        bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
        libId = SymbolId("crossproducer.Lib")
      } yield expect(tastyIds.contains(libId)) and expect(bytecodeIds.contains(libId))
    }
  }

  test("companion object id agrees between TastyIndexer and BytecodeIndexer") {
    ignore(pendingReason) *> {
      for {
        tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
        bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
        // Both should converge on crossproducer.Lib (module Object kind, no $ in canonical form)
        libObjId = SymbolId("crossproducer.Lib")
      } yield expect(tastyIds.exists(_ == libObjId)) and expect(bytecodeIds.exists(_ == libObjId))
    }
  }

  test("overloaded method compute(Int) id agrees between TastyIndexer and BytecodeIndexer") {
    // Phase 1 exit signal: canonical example of the Cls.foo vs Cls#foo bug.
    // TastyIndexer: crossproducer.Lib.compute  BytecodeIndexer: crossproducer.Lib#compute
    ignore(pendingReason) *> {
      for {
        tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
        bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
        computeId = SymbolId("crossproducer.Lib.compute")
      } yield expect(tastyIds.contains(computeId)) and expect(bytecodeIds.contains(computeId))
    }
  }

  test("inner class id agrees between TastyIndexer and BytecodeIndexer") {
    ignore(pendingReason) *> {
      for {
        tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
        bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
        innerId = SymbolId("crossproducer.Lib.Inner")
      } yield expect(tastyIds.contains(innerId)) and expect(bytecodeIds.contains(innerId))
    }
  }

  // ── Java vs. Bytecode ────────────────────────────────────────────────────────

  test("Java class LibJ id agrees between JavaIndexer and BytecodeIndexer") {
    ignore(pendingReason) *> {
      for {
        javaIds     <- javaSymbols.map(_.map(_.id).toSet)
        bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
        libJId = SymbolId("crossproducer.LibJ")
      } yield expect(javaIds.contains(libJId)) and expect(bytecodeIds.contains(libJId))
    }
  }

  test("Java overloaded method id agrees between JavaIndexer and BytecodeIndexer") {
    // JavaIndexer uses sym.fullName → crossproducer.LibJ.compute
    // BytecodeIndexer uses JVM format → crossproducer.LibJ#compute
    ignore(pendingReason) *> {
      for {
        javaIds     <- javaSymbols.map(_.map(_.id).toSet)
        bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
        computeId = SymbolId("crossproducer.LibJ.compute")
      } yield expect(javaIds.contains(computeId)) and expect(bytecodeIds.contains(computeId))
    }
  }
}
