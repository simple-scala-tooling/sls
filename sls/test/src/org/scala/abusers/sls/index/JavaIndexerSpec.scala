package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri
import weaver.*

import java.nio.file.Paths

object JavaIndexerSpec extends SimpleIOSuite {

  private def resourceAbs(name: String): AbsolutePath = {
    val url = Option(getClass.getResource(s"/java-frontend/$name"))
      .getOrElse(sys.error(s"missing test resource: /java-frontend/$name"))
    AbsolutePath(Paths.get(url.toURI))
  }

  private val sources = List("Greeter.java", "Shapes.java", "Square.java").map(resourceAbs)

  private val indexed: IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    JavaIndexer.forProject("test-target").indexFiles(sources, Nil).memoize.flatten

  private def allSymbols: IO[List[IndexedSymbol]] = indexed.map(_.values.flatMap(_._1).toList)
  private def allRefs: IO[List[SymbolReference]]  = indexed.map(_.values.flatMap(_._2).toList)
  private def findSymbol(name: String): IO[Option[IndexedSymbol]] = allSymbols.map(_.find(_.name == name))

  test("Java class indexed as Class kind") {
    findSymbol("Square").map(s => expect(s.exists(_.kind == SymbolKind.Class)))
  }

  test("Java interface indexed as Trait kind") {
    findSymbol("Shapes").map(s => expect(s.exists(_.kind == SymbolKind.Trait)))
  }

  test("Java method indexed as Method kind with owner set") {
    for {
      syms <- allSymbols
      areaOnSquare = syms.find(s =>
        s.name == "area" && s.kind == SymbolKind.Method && s.owner.exists(_.value == "fixture.Square")
      )
    } yield expect(areaOnSquare.isDefined)
  }

  test("Java fields indexed as Field kind with visibility") {
    for {
      syms <- allSymbols
      side  = syms.find(_.name == "side")
      id    = syms.find(s => s.name == "id" && s.kind == SymbolKind.Field)
      label = syms.find(_.name == "label")
    } yield expect(side.exists(_.visibility == Visibility.Private)) and
      expect(id.exists(_.visibility == Visibility.Protected)) and
      expect(label.exists(_.visibility == Visibility.Public))
  }

  test("constructor indexed as Constructor kind") {
    for {
      syms <- allSymbols
      ctors = syms.filter(_.kind == SymbolKind.Constructor)
    } yield expect(ctors.exists(_.owner.exists(_.value.contains("Square"))))
  }

  test("implements relationship produces parent + Extends reference") {
    for {
      syms <- allSymbols
      refs <- allRefs
      square      = syms.find(_.name == "Square")
      extendsRefs = refs.filter(_.referenceKind == ReferenceKind.Extends)
    } yield expect(square.exists(_.parents.exists(_.value.contains("Shapes")))) and
      expect(extendsRefs.exists(_.symbol.value.contains("Shapes")))
  }

  test("origin is ProjectJavaSource") {
    for {
      syms <- allSymbols
    } yield expect(syms.nonEmpty) and expect(syms.forall(_.origin.isInstanceOf[SymbolOrigin.ProjectJavaSource]))
  }

  test("forDependency tags origin as DependencySource with jarPath") {
    val jar = "/some/path/foo-1.0.0-sources.jar"
    for {
      results <- JavaIndexer.forDependency(jar).indexFiles(sources, Nil)
      syms = results.values.flatMap(_._1).toList
    } yield expect(syms.nonEmpty) and
      expect(syms.forall(_.origin match {
        case SymbolOrigin.DependencySource(j, _) => j == jar
        case _                                   => false
      }))
  }

  test("symbols have locations") {
    for {
      syms <- allSymbols
      // method parameters can be Param and skipped; check the class/method/field-level symbols
      named = syms.filter(s => Set[SymbolKind](SymbolKind.Class, SymbolKind.Trait, SymbolKind.Method, SymbolKind.Field, SymbolKind.Constructor).contains(s.kind))
    } yield expect(named.forall(_.location.isDefined))
  }
}
