package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.SourceUri
import weaver.*

object ProjectIndexSpec extends SimpleIOSuite {

  private val fileA = SourceUri("file:///src/A.scala")
  private val fileB = SourceUri("file:///src/B.scala")

  private def sym(name: String, file: SourceUri, parents: List[SymbolId] = Nil): IndexedSymbol =
    IndexedSymbol(
      id = SymbolId(s"pkg.$name"),
      name = name,
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(file, 0, 0, 0, 10)),
      origin = SymbolOrigin.ProjectTasty("target", file),
      parents = parents,
      typeSignature = None,
    )

  private def ref(symbolName: String, file: SourceUri): SymbolReference =
    SymbolReference(
      symbol = SymbolId(s"pkg.$symbolName"),
      location = Location(file, 5, 0, 5, 10),
      referenceKind = ReferenceKind.Call,
    )

  test("insert symbols and query by ID") {
    for {
      idx    <- ProjectIndex.empty
      _      <- idx.updateFiles(Map(fileA -> (List(sym("Foo", fileA)), Nil)))
      result <- idx.getSymbol(SymbolId("pkg.Foo"))
    } yield expect(result.exists(_.name == "Foo"))
  }

  test("query by name") {
    for {
      idx    <- ProjectIndex.empty
      _      <- idx.updateFiles(Map(fileA -> (List(sym("Foo", fileA)), Nil)))
      result <- idx.getSymbolsByName("Foo")
    } yield expect(result.size == 1 && result.head.name == "Foo")
  }

  test("query symbols in file") {
    for {
      idx    <- ProjectIndex.empty
      _      <- idx.updateFiles(Map(fileA -> (List(sym("Foo", fileA), sym("Bar", fileA)), Nil)))
      result <- idx.getSymbolsInFile(fileA)
    } yield expect(result.size == 2)
  }

  test("removeFiles clears exactly that file's symbols") {
    for {
      idx <- ProjectIndex.empty
      _   <- idx.updateFiles(
        Map(
          fileA -> (List(sym("Foo", fileA)), Nil),
          fileB -> (List(sym("Bar", fileB)), Nil),
        )
      )
      _   <- idx.removeFiles(Set(fileA))
      foo <- idx.getSymbol(SymbolId("pkg.Foo"))
      bar <- idx.getSymbol(SymbolId("pkg.Bar"))
    } yield expect(foo.isEmpty) and expect(bar.isDefined)
  }

  test("updateFiles with same URI replaces old symbols") {
    for {
      idx <- ProjectIndex.empty
      _   <- idx.updateFiles(Map(fileA -> (List(sym("Foo", fileA)), Nil)))
      _   <- idx.updateFiles(Map(fileA -> (List(sym("Baz", fileA)), Nil)))
      foo <- idx.getSymbol(SymbolId("pkg.Foo"))
      baz <- idx.getSymbol(SymbolId("pkg.Baz"))
    } yield expect(foo.isEmpty) and expect(baz.isDefined)
  }

  test("prefix search finds matching symbols") {
    for {
      idx <- ProjectIndex.empty
      _   <- idx.updateFiles(Map(fileA -> (List(sym("FooBar", fileA), sym("FooBaz", fileA), sym("Xyz", fileA)), Nil)))
      result <- idx.searchSymbols("foo")
    } yield expect(result.size == 2) and expect(result.forall(_.name.startsWith("Foo")))
  }

  test("CamelCase search finds matching symbols") {
    for {
      idx    <- ProjectIndex.empty
      _      <- idx.updateFiles(Map(fileA -> (List(sym("AbstractListType", fileA), sym("ArrayBuffer", fileA)), Nil)))
      result <- idx.searchSymbols("ALT")
    } yield expect(result.size == 1) and expect(result.head.name == "AbstractListType")
  }

  test("getReferences returns refs across files") {
    for {
      idx <- ProjectIndex.empty
      _   <- idx.updateFiles(
        Map(
          fileA -> (List(sym("Foo", fileA)), List(ref("Bar", fileA))),
          fileB -> (Nil, List(ref("Bar", fileB))),
        )
      )
      result <- idx.getReferences(SymbolId("pkg.Bar"))
    } yield expect(result.size == 2)
  }

  test("getReferences order is prepend-on-insert within a file — Phase 3 regression guard") {
    // ProjectIndex.addFileToState prepends each new ref (ref :: list).
    // Insertion order for [r1, r2, r3] produces list [r3, r2, r1].
    // Phase 3's CoreState.add rewrite must not silently change this.
    val r1 = SymbolReference(SymbolId("pkg.T"), Location(fileA, 1, 0, 1, 5), ReferenceKind.Call)
    val r2 = SymbolReference(SymbolId("pkg.T"), Location(fileA, 2, 0, 2, 5), ReferenceKind.Call)
    val r3 = SymbolReference(SymbolId("pkg.T"), Location(fileA, 3, 0, 3, 5), ReferenceKind.Call)
    for {
      idx  <- ProjectIndex.empty
      _    <- idx.updateFiles(Map(fileA -> (Nil, List(r1, r2, r3))))
      refs <- idx.getReferences(SymbolId("pkg.T"))
    } yield expect(refs.map(_.location.startLine) == List(3, 2, 1))
  }

  test("updateFiles replaces — not appends — refs for the same URI") {
    // A second updateFiles for the same URI must remove old refs before inserting new.
    // Without removal, repeated compile events accumulate stale references.
    val old = SymbolReference(SymbolId("pkg.T"), Location(fileA, 1, 0, 1, 5), ReferenceKind.Call)
    val neu = SymbolReference(SymbolId("pkg.T"), Location(fileA, 9, 0, 9, 5), ReferenceKind.Call)
    for {
      idx  <- ProjectIndex.empty
      _    <- idx.updateFiles(Map(fileA -> (Nil, List(old))))
      _    <- idx.updateFiles(Map(fileA -> (Nil, List(neu))))
      refs <- idx.getReferences(SymbolId("pkg.T"))
    } yield expect(refs.size == 1) and expect(refs.head.location.startLine == 9)
  }

  test("getSubtypes correct after insertion and clean after removal") {
    val parent = sym("Parent", fileA)
    val child  = sym("Child", fileA, parents = List(SymbolId("pkg.Parent")))
    for {
      idx       <- ProjectIndex.empty
      _         <- idx.updateFiles(Map(fileA -> (List(parent, child), Nil)))
      subs      <- idx.getSubtypes(SymbolId("pkg.Parent"))
      _         <- idx.removeFiles(Set(fileA))
      subsAfter <- idx.getSubtypes(SymbolId("pkg.Parent"))
    } yield expect(subs == Set(SymbolId("pkg.Child"))) and expect(subsAfter.isEmpty)
  }

  test("empty index returns empty for all queries") {
    for {
      idx    <- ProjectIndex.empty
      sym    <- idx.getSymbol(SymbolId("x"))
      byName <- idx.getSymbolsByName("x")
      search <- idx.searchSymbols("x")
      inFile <- idx.getSymbolsInFile(fileA)
      refs   <- idx.getReferences(SymbolId("x"))
      subs   <- idx.getSubtypes(SymbolId("x"))
    } yield expect(sym.isEmpty) and expect(byName.isEmpty) and expect(search.isEmpty) and
      expect(inFile.isEmpty) and expect(refs.isEmpty) and expect(subs.isEmpty)
  }

  test("concurrent updateFiles don't corrupt state") {
    for {
      idx <- ProjectIndex.empty
      files = (0 until 100).map { i =>
        val uri = SourceUri(s"file:///src/F$i.scala")
        uri -> (List(sym(s"Sym$i", uri)), Nil)
      }
      _       <- IO.parTraverseN(10)(files.toList)(f => idx.updateFiles(Map(f)))
      results <- IO.parTraverseN(10)(files.toList.map(_._1))(idx.getSymbolsInFile)
    } yield expect(results.forall(_.size == 1))
  }
}
