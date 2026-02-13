package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*
import java.net.URI

object SymbolIndexSpec extends SimpleIOSuite {

  private val fileA = URI("file:///src/A.scala")

  private def projSym(
      name: String,
      parents: List[SymbolId] = Nil,
      kind: SymbolKind = SymbolKind.Class,
      startLine: Int = 0,
      startCol: Int = 0,
      endLine: Int = 0,
      endCol: Int = 10,
  ): IndexedSymbol =
    IndexedSymbol(
      id = SymbolId(s"pkg.$name"),
      name = name,
      kind = kind,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(fileA, startLine, startCol, endLine, endCol)),
      origin = SymbolOrigin.ProjectTasty("target", fileA),
      parents = parents,
      typeSignature = None,
    )

  private def depSym(
      name: String,
      parents: List[SymbolId] = Nil,
      kind: SymbolKind = SymbolKind.Class,
  ): IndexedSymbol =
    IndexedSymbol(
      id = SymbolId(s"pkg.$name"),
      name = name,
      kind = kind,
      visibility = Visibility.Public,
      owner = None,
      location = None,
      origin = SymbolOrigin.DependencyClassfile("lib/a.jar"),
      parents = parents,
      typeSignature = None,
    )

  private def mkIndex(
      projSyms: List[IndexedSymbol] = Nil,
      projRefs: List[SymbolReference] = Nil,
      depSyms: List[IndexedSymbol] = Nil,
  ): IO[SymbolIndex] =
    for {
      proj <- ProjectIndex.empty
      dep <- DependencyIndex.empty
      _ <- if projSyms.nonEmpty || projRefs.nonEmpty then
        proj.updateFiles(Map(fileA -> (projSyms, projRefs)))
      else IO.unit
      _ <- if depSyms.nonEmpty then dep.addJar("lib/a.jar", depSyms) else IO.unit
    } yield SymbolIndex(proj, dep)

  test("symbol in project only → found") {
    for {
      idx <- mkIndex(projSyms = List(projSym("Foo")))
      result <- idx.getSymbol(SymbolId("pkg.Foo"))
    } yield expect(result.exists(_.name == "Foo"))
  }

  test("symbol in dependency only → found") {
    for {
      idx <- mkIndex(depSyms = List(depSym("Bar")))
      result <- idx.getSymbol(SymbolId("pkg.Bar"))
    } yield expect(result.exists(_.name == "Bar"))
  }

  test("symbol in both → project version returned") {
    val proj = projSym("Foo")
    val dep = depSym("Foo")
    for {
      idx <- mkIndex(projSyms = List(proj), depSyms = List(dep))
      result <- idx.getSymbol(SymbolId("pkg.Foo"))
    } yield expect(result.exists(_.origin.isInstanceOf[SymbolOrigin.ProjectTasty]))
  }

  test("searchSymbols returns from both tiers") {
    for {
      idx <- mkIndex(projSyms = List(projSym("FooBar")), depSyms = List(depSym("FooBaz")))
      result <- idx.searchSymbols("foo")
    } yield expect(result.size == 2) and
      expect(result.exists(_.name == "FooBar")) and
      expect(result.exists(_.name == "FooBaz"))
  }

  test("searchSymbols deduplicates same ID across tiers") {
    for {
      idx <- mkIndex(projSyms = List(projSym("Foo")), depSyms = List(depSym("Foo")))
      result <- idx.searchSymbols("foo")
    } yield expect(result.size == 1) and
      expect(result.head.origin.isInstanceOf[SymbolOrigin.ProjectTasty])
  }

  test("getReferences returns only project references") {
    val r = SymbolReference(SymbolId("pkg.Bar"), Location(fileA, 5, 0, 5, 10), ReferenceKind.Call)
    for {
      idx <- mkIndex(projRefs = List(r))
      result <- idx.getReferences(SymbolId("pkg.Bar"))
    } yield expect(result.size == 1)
  }

  test("getSubtypes merges from both tiers") {
    val projParent = projSym("Parent")
    val projChild = projSym("ChildA", parents = List(SymbolId("pkg.Parent")))
    val depChild = depSym("ChildB", parents = List(SymbolId("pkg.Parent")))
    for {
      idx <- mkIndex(projSyms = List(projParent, projChild), depSyms = List(depChild))
      subs <- idx.getSubtypes(SymbolId("pkg.Parent"))
    } yield expect(subs == Set(SymbolId("pkg.ChildA"), SymbolId("pkg.ChildB")))
  }

  test("getImplementations follows transitive subtype chain") {
    // Trait -> Abstract -> Concrete
    val traitSym = projSym("MyTrait", kind = SymbolKind.Trait)
    val abstractSym = projSym("Abstract", parents = List(SymbolId("pkg.MyTrait")), kind = SymbolKind.Trait)
    val concrete = projSym("Concrete", parents = List(SymbolId("pkg.Abstract")))
    for {
      idx <- mkIndex(projSyms = List(traitSym, abstractSym, concrete))
      impls <- idx.getImplementations(SymbolId("pkg.MyTrait"))
    } yield expect(impls.size == 1) and expect(impls.head.name == "Concrete")
  }

  test("resolveSymbolAtPosition finds correct symbol at cursor") {
    // Outer class spans lines 0-20, inner method spans lines 5-10
    val outer = projSym("Outer", startLine = 0, startCol = 0, endLine = 20, endCol = 0)
    val inner = projSym("inner", startLine = 5, startCol = 2, endLine = 10, endCol = 2, kind = SymbolKind.Method)
    for {
      idx <- mkIndex(projSyms = List(outer, inner))
      result <- idx.resolveSymbolAtPosition(fileA, 7, 5)
    } yield expect(result.exists(_.name == "inner"))
  }

  test("resolveSymbolAtPosition returns None for non-matching position") {
    val sym = projSym("Foo", startLine = 0, startCol = 0, endLine = 5, endCol = 0)
    for {
      idx <- mkIndex(projSyms = List(sym))
      result <- idx.resolveSymbolAtPosition(fileA, 10, 0)
    } yield expect(result.isEmpty)
  }

  test("getSupertypes uses project-priority symbol") {
    val proj = projSym("Child", parents = List(SymbolId("pkg.ProjParent")))
    val dep = depSym("Child", parents = List(SymbolId("pkg.DepParent")))
    for {
      idx <- mkIndex(projSyms = List(proj), depSyms = List(dep))
      supers <- idx.getSupertypes(SymbolId("pkg.Child"))
    } yield expect(supers == List(SymbolId("pkg.ProjParent")))
  }
}
