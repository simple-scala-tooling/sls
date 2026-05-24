package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

/** Verifies that TastyIndexer, BytecodeIndexer, and JavaIndexer emit the same SymbolId for the same source-level
  * symbols in the cross-producer fixture (`crossProducerFixture`).
  *
  * The fixture JAR is pre-compiled and published to `~/.m2` by `sls.test.forkArgs` — see `IndexTestFixtures`. No
  * runtime compilation.
  *
  * Phase 1 exit signal: these assertions used to be `ignore(...)`'d because TastyIndexer emitted
  * `crossproducer.Lib.compute` while BytecodeIndexer emitted `crossproducer.Lib#compute`. The canonical [[SymbolId]]
  * removes that drift; the tests run for real now.
  */
object CrossProducerSpec extends SimpleIOSuite {

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
    for {
      tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
      bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
      libId = SymbolId.tpe(List("crossproducer"), Nil, "Lib")
    } yield expect(tastyIds.contains(libId)) and expect(bytecodeIds.contains(libId))
  }

  test("companion object id agrees between TastyIndexer and BytecodeIndexer") {
    for {
      tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
      bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
      // Companion object is a TERM (the singleton value), distinct from the class id of the same name.
      libObjId = SymbolId.term(List("crossproducer"), Nil, "Lib")
    } yield expect(tastyIds.contains(libObjId)) and expect(bytecodeIds.contains(libObjId))
  }

  test("overloaded method compute(Int) id agrees between TastyIndexer and BytecodeIndexer") {
    // Phase 1 exit signal: canonical example of the Cls.foo vs Cls#foo bug.
    for {
      tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
      bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
      computeId = SymbolId.term(List("crossproducer"), List("Lib"), "compute")
    } yield expect(tastyIds.contains(computeId)) and expect(bytecodeIds.contains(computeId))
  }

  test("inner class id agrees between TastyIndexer and BytecodeIndexer") {
    for {
      tastyIds    <- tastySymbols.map(_.map(_.id).toSet)
      bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
      innerId = SymbolId.tpe(List("crossproducer"), List("Lib"), "Inner")
    } yield expect(tastyIds.contains(innerId)) and expect(bytecodeIds.contains(innerId))
  }

  // ── Java vs. Bytecode ────────────────────────────────────────────────────────

  test("Java class LibJ id agrees between JavaIndexer and BytecodeIndexer") {
    for {
      javaIds     <- javaSymbols.map(_.map(_.id).toSet)
      bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
      libJId = SymbolId.tpe(List("crossproducer"), Nil, "LibJ")
    } yield expect(javaIds.contains(libJId)) and expect(bytecodeIds.contains(libJId))
  }

  test("Java overloaded method id agrees between JavaIndexer and BytecodeIndexer") {
    for {
      javaIds     <- javaSymbols.map(_.map(_.id).toSet)
      bytecodeIds <- bytecodeSymbols.map(_.map(_.id).toSet)
      computeId = SymbolId.term(List("crossproducer"), List("LibJ"), "compute")
    } yield expect(javaIds.contains(computeId)) and expect(bytecodeIds.contains(computeId))
  }
}
