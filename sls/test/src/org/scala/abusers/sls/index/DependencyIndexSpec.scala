package org.scala.abusers.sls.index

import org.scala.abusers.sls.SourceUri
import weaver.*

object DependencyIndexSpec extends SimpleIOSuite {

  private val jarA = "lib/a.jar"
  private val jarB = "lib/b.jar"

  private def sym(
      name: String,
      jarPath: String,
      parents: List[SymbolId] = Nil,
      location: Option[Location] = None,
  ): IndexedSymbol =
    IndexedSymbol(
      id = IndexTestFixtures.tid(s"pkg.$name"),
      name = name,
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = location,
      origin = SymbolOrigin.DependencyClassfile(jarPath),
      parents = parents,
      typeSignature = None,
    )

  test("add symbols for a JAR and query by ID") {
    for {
      idx    <- DependencyIndex.empty
      _      <- idx.addJar(jarA, List(sym("Foo", jarA)))
      result <- idx.getSymbol(IndexTestFixtures.tid("pkg.Foo"))
    } yield expect(result.exists(_.name == "Foo"))
  }

  test("query by name") {
    for {
      idx    <- DependencyIndex.empty
      _      <- idx.addJar(jarA, List(sym("Foo", jarA)))
      result <- idx.getSymbolsByName("Foo")
    } yield expect(result.size == 1 && result.head.name == "Foo")
  }

  test("workspace symbol search across multiple JARs") {
    for {
      idx    <- DependencyIndex.empty
      _      <- idx.addJar(jarA, List(sym("FooBar", jarA), sym("FooBaz", jarA)))
      _      <- idx.addJar(jarB, List(sym("FooQux", jarB), sym("Xyz", jarB)))
      result <- idx.searchSymbols("foo")
    } yield expect(result.size == 3) and expect(result.forall(_.name.startsWith("Foo")))
  }

  test("CamelCase search across JARs") {
    for {
      idx    <- DependencyIndex.empty
      _      <- idx.addJar(jarA, List(sym("AbstractListType", jarA)))
      _      <- idx.addJar(jarB, List(sym("ArrayBuffer", jarB)))
      result <- idx.searchSymbols("ALT")
    } yield expect(result.size == 1) and expect(result.head.name == "AbstractListType")
  }

  test("bloom filter pre-filtering: JAR-A has Foo, query Bar not present") {
    for {
      idx    <- DependencyIndex.empty
      _      <- idx.addJar(jarA, List(sym("Foo", jarA)))
      hasFoo <- idx.jarMightContain(jarA, "Foo")
      hasBar <- idx.jarMightContain(jarA, "Bar")
    } yield expect(hasFoo) and expect(!hasBar)
  }

  test("updateLocations changes location from None to Some") {
    for {
      idx    <- DependencyIndex.empty
      _      <- idx.addJar(jarA, List(sym("Foo", jarA)))
      before <- idx.getSymbol(IndexTestFixtures.tid("pkg.Foo"))
      loc = Location(SourceUri("file:///src/Foo.scala"), 10, 0, 10, 20)
      _     <- idx.updateLocations(Map(IndexTestFixtures.tid("pkg.Foo") -> loc))
      after <- idx.getSymbol(IndexTestFixtures.tid("pkg.Foo"))
    } yield expect(before.flatMap(_.location).isEmpty) and
      expect(after.flatMap(_.location).contains(loc))
  }

  test("getSubtypes works across JARs") {
    val parent = sym("Parent", jarA)
    val childA = sym("ChildA", jarA, parents = List(IndexTestFixtures.tid("pkg.Parent")))
    val childB = sym("ChildB", jarB, parents = List(IndexTestFixtures.tid("pkg.Parent")))
    for {
      idx  <- DependencyIndex.empty
      _    <- idx.addJar(jarA, List(parent, childA))
      _    <- idx.addJar(jarB, List(childB))
      subs <- idx.getSubtypes(IndexTestFixtures.tid("pkg.Parent"))
    } yield expect(subs == Set(IndexTestFixtures.tid("pkg.ChildA"), IndexTestFixtures.tid("pkg.ChildB")))
  }

  test("getSupertypes returns parents") {
    val child = sym("Child", jarA, parents = List(IndexTestFixtures.tid("pkg.Parent")))
    for {
      idx     <- DependencyIndex.empty
      _       <- idx.addJar(jarA, List(child))
      parents <- idx.getSupertypes(IndexTestFixtures.tid("pkg.Child"))
    } yield expect(parents == List(IndexTestFixtures.tid("pkg.Parent")))
  }

  test("empty index returns empty for all queries") {
    for {
      idx    <- DependencyIndex.empty
      sym    <- idx.getSymbol(IndexTestFixtures.tid("x"))
      byName <- idx.getSymbolsByName("x")
      search <- idx.searchSymbols("x")
      subs   <- idx.getSubtypes(IndexTestFixtures.tid("x"))
      supers <- idx.getSupertypes(IndexTestFixtures.tid("x"))
      bloom  <- idx.jarMightContain("any.jar", "x")
    } yield expect(sym.isEmpty) and expect(byName.isEmpty) and expect(search.isEmpty) and
      expect(subs.isEmpty) and expect(supers.isEmpty) and expect(!bloom)
  }
}
