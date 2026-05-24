package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

object IndexTypesSpec extends SimpleIOSuite {

  test("SymbolId equality is structural") {
    val a = SymbolId.tpe(List("a"), Nil, "B")
    val b = SymbolId.tpe(List("a"), Nil, "B")
    val c = SymbolId.tpe(List("a"), Nil, "C")
    IO(expect(a == b) && expect(a != c))
  }

  test("type vs term ids with the same name are not equal") {
    val tpeId  = SymbolId.tpe(List("crossproducer"), Nil, "Lib")
    val termId = SymbolId.term(List("crossproducer"), Nil, "Lib")
    IO(expect(tpeId != termId))
  }

  test("fromJvm parses a top-level class") {
    val id = SymbolId.fromJvm("crossproducer/Lib", memberName = None)
    IO(expect(id == SymbolId.tpe(List("crossproducer"), Nil, "Lib")))
  }

  test("fromJvm parses a method on a top-level class") {
    val id = SymbolId.fromJvm("crossproducer/Lib", memberName = Some("compute"))
    IO(expect(id == SymbolId.term(List("crossproducer"), List("Lib"), "compute")))
  }

  test("fromJvm strips trailing $ on a module class into a term id") {
    val id = SymbolId.fromJvm("crossproducer/Lib$", memberName = None)
    IO(expect(id == SymbolId.term(List("crossproducer"), Nil, "Lib")))
  }

  test("fromJvm handles inner classes") {
    val id = SymbolId.fromJvm("com/example/Outer$Inner", memberName = None)
    IO(expect(id == SymbolId.tpe(List("com", "example"), List("Outer"), "Inner")))
  }

  test("fromSemanticDb parses a type symbol") {
    val id = SymbolId.fromSemanticDb("scala/collection/immutable/List#")
    IO(expect(id == SymbolId.tpe(List("scala", "collection", "immutable"), Nil, "List")))
  }

  test("fromSemanticDb parses a term member on a class") {
    val id = SymbolId.fromSemanticDb("scala/Predef.println(+1).")
    IO(expect(id == SymbolId.term(List("scala"), List("Predef"), "println")))
  }

  test("fromSemanticDb collapses `.` and `#` separators in the owner chain") {
    // Lock-in for the Phase 1 simplification documented on `fromSemanticDb`: owner separators are not preserved,
    // so an inner-class term symbol coalesces to the same id regardless of which form the SemanticDB writer chose.
    val a = SymbolId.fromSemanticDb("pkg/Outer.Inner#run().")
    val b = SymbolId.fromSemanticDb("pkg/Outer#Inner.run().")
    IO(
      expect(a == SymbolId.term(List("pkg"), List("Outer", "Inner"), "run")) &&
        expect(a == b)
    )
  }

  test("fromJava routes through fromTasty and respects the isType flag") {
    val cls    = SymbolId.fromJava(List("pkg"), Nil, "LibJ", isType = true)
    val method = SymbolId.fromJava(List("pkg"), List("LibJ"), "compute", isType = false)
    IO(
      expect(cls == SymbolId.tpe(List("pkg"), Nil, "LibJ")) &&
        expect(method == SymbolId.term(List("pkg"), List("LibJ"), "compute"))
    )
  }

  test("render produces a stable human-readable string") {
    val cls   = SymbolId.tpe(List("crossproducer"), Nil, "Lib")
    val mthod = SymbolId.term(List("crossproducer"), List("Lib"), "compute")
    IO(
      expect(cls.render == "crossproducer.Lib") &&
        expect(mthod.render == "crossproducer.Lib.compute()")
    )
  }

  test("render includes Member.disambig once it's set (Phase 2 forward-compat)") {
    val overloaded = SymbolId(List("pkg"), List("Lib"), "compute", Some(Member(Some("(I)I"))))
    IO(expect(overloaded.render == "pkg.Lib.compute((I)I)"))
  }
}
