package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

object IndexTypesSpec extends SimpleIOSuite {

  test("SymbolId round-trips through .value") {
    val id = SymbolId("scala/Option#")
    IO(expect(SymbolId(id.value) == id))
  }

  test("SymbolId equality") {
    IO(expect(SymbolId("a.B#") == SymbolId("a.B#")) &&
      expect(SymbolId("a.B#") != SymbolId("a.C#")))
  }
}
