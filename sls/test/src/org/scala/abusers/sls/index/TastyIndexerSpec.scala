package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri
import weaver.*

object TastyIndexerSpec extends SimpleIOSuite {

  private val fixtures: Map[String, String] = Map(
    "SimpleClass.scala" ->
      """package fixture
        |class SimpleClass {
        |  def greet(name: String): String = s"Hello, $name"
        |}
        |""".stripMargin,
    "TraitWithAbstract.scala" ->
      """package fixture
        |trait Animal {
        |  def sound: String
        |  def name: String = "unknown"
        |}
        |""".stripMargin,
    "CaseClassExample.scala" ->
      """package fixture
        |case class Point(x: Int, y: Int)
        |""".stripMargin,
    "EnumExample.scala" ->
      """package fixture
        |enum Color {
        |  case Red, Green, Blue
        |}
        |""".stripMargin,
    "SealedHierarchy.scala" ->
      """package fixture
        |sealed trait Shape
        |class Circle(radius: Double) extends Shape
        |class Rectangle(w: Double, h: Double) extends Shape
        |""".stripMargin,
    "Inheritance.scala" ->
      """package fixture
        |trait Greeter {
        |  def greet(): String
        |}
        |class FriendlyGreeter extends Greeter {
        |  override def greet(): String = "Hi!"
        |}
        |""".stripMargin,
    "VisibilityExample.scala" ->
      """package fixture
        |class VisibilityExample {
        |  private val secret: Int = 42
        |  protected def helper(): Unit = ()
        |  def publicMethod(): String = "visible"
        |}
        |""".stripMargin,
    "MultipleTopLevel.scala" ->
      """package fixture
        |class TopA
        |class TopB
        |""".stripMargin,
  )

  private def compileAndIndex: IO[(os.Path, Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])])] = IO
    .blocking {
      val tmpDir = os.temp.dir(prefix = "tasty-indexer-test")
      val srcDir = tmpDir / "src"
      val outDir = tmpDir / "out"
      os.makeDir.all(srcDir)
      os.makeDir.all(outDir)

      fixtures.foreach { case (name, content) =>
        os.write(srcDir / name, content)
      }

      val sourceFiles = os.list(srcDir).filter(_.ext == "scala").map(_.toString).toArray
      val args        = Array("-d", outDir.toString, "-usejavacp") ++ sourceFiles

      dotty.tools.dotc.Main.process(args)

      (tmpDir, ())
    }
    .flatMap { case (tmpDir, _) =>
      val outDir  = AbsolutePath((tmpDir / "out").toNIO)
      val indexer = TastyIndexer("test-target")
      indexer.indexDirectory(outDir, Nil).map(result => (tmpDir, result))
    }

  private lazy val indexed: IO[(os.Path, Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])])] =
    compileAndIndex.memoize.flatten

  private def allSymbols: IO[List[IndexedSymbol]] =
    indexed.map(_._2.values.flatMap(_._1).toList)

  private def allRefs: IO[List[SymbolReference]] =
    indexed.map(_._2.values.flatMap(_._2).toList)

  private def findById(id: SymbolId): IO[Option[IndexedSymbol]] =
    allSymbols.map(_.find(_.id == id))

  test("simple class with method — both symbols extracted") {
    for {
      cls    <- findById(SymbolId("fixture.SimpleClass"))
      method <- findById(SymbolId("fixture.SimpleClass.greet"))
    } yield expect(cls.isDefined) and
      expect(cls.exists(_.kind == SymbolKind.Class)) and
      expect(method.isDefined) and
      expect(method.exists(_.kind == SymbolKind.Method))
  }

  test("trait with abstract method — Trait kind") {
    for {
      sym <- findById(SymbolId("fixture.Animal"))
    } yield expect(sym.exists(_.kind == SymbolKind.Trait))
  }

  test("case class — class extracted") {
    for {
      sym <- findById(SymbolId("fixture.Point"))
    } yield expect(sym.exists(_.kind == SymbolKind.Class))
  }

  test("enum with cases — Enum kind and EnumCase kind for each case") {
    for {
      sym <- findById(SymbolId("fixture.Color"))
      syms <- allSymbols
      red   = syms.find(s => s.name == "Red"   && s.kind == SymbolKind.EnumCase)
      green = syms.find(s => s.name == "Green" && s.kind == SymbolKind.EnumCase)
      blue  = syms.find(s => s.name == "Blue"  && s.kind == SymbolKind.EnumCase)
    } yield expect(sym.exists(_.kind == SymbolKind.Enum)) and
      expect(red.isDefined) and expect(green.isDefined) and expect(blue.isDefined)
  }

  test("sealed trait children — parent/child relationships") {
    for {
      circle <- findById(SymbolId("fixture.Circle"))
      rect   <- findById(SymbolId("fixture.Rectangle"))
    } yield expect(circle.exists(_.parents.contains(SymbolId("fixture.Shape")))) and
      expect(rect.exists(_.parents.contains(SymbolId("fixture.Shape"))))
  }

  test("class extending trait — parents list correct") {
    for {
      sym <- findById(SymbolId("fixture.FriendlyGreeter"))
    } yield expect(sym.exists(_.parents.contains(SymbolId("fixture.Greeter"))))
  }

  test("method override — Override reference points to overridden symbol") {
    for {
      refs <- allRefs
      overrideRefs = refs.filter(_.referenceKind == ReferenceKind.Override)
    } yield expect(overrideRefs.exists(_.symbol == SymbolId("fixture.Greeter.greet")))
  }

  test("multiple top-level defs — all keyed to same source URI") {
    for {
      result <- indexed.map(_._2)
      multiUri = result.find { case (_, (syms, _)) =>
        syms.exists(_.id == SymbolId("fixture.TopA")) && syms.exists(_.id == SymbolId("fixture.TopB"))
      }
    } yield expect(multiUri.isDefined)
  }

  test("synthetic symbols not indexed") {
    for {
      syms <- allSymbols
      anons    = syms.filter(_.name.contains("$anon"))
      evidence = syms.filter(_.name.contains("$evidence"))
    } yield expect(anons.isEmpty) and expect(evidence.isEmpty)
  }

  test("visibility — private/protected/public correctly determined") {
    for {
      secret <- findById(SymbolId("fixture.VisibilityExample.secret"))
      helper <- findById(SymbolId("fixture.VisibilityExample.helper"))
      pub    <- findById(SymbolId("fixture.VisibilityExample.publicMethod"))
    } yield expect(secret.exists(_.visibility == Visibility.Private)) and
      expect(helper.exists(_.visibility == Visibility.Protected)) and
      expect(pub.exists(_.visibility == Visibility.Public))
  }

  test("all symbols have valid locations") {
    for {
      syms <- allSymbols
    } yield expect(
      syms.forall(s => s.location.exists(loc => loc.startLine >= 0 && loc.endLine >= loc.startLine))
    )
  }

  test("extends references generated for parent types") {
    for {
      refs <- allRefs
      extendsRefs = refs.filter(_.referenceKind == ReferenceKind.Extends)
    } yield expect(extendsRefs.nonEmpty)
  }

  test("round-trip determinism — same input indexed twice produces identical symbol ids") {
    for {
      first  <- compileAndIndex.map(_._2)
      second <- compileAndIndex.map(_._2)
      firstIds  = first.values.flatMap(_._1).map(_.id).toSet
      secondIds = second.values.flatMap(_._1).map(_.id).toSet
    } yield expect(firstIds == secondIds)
  }

  test("TastyIndexer reference output is stable across runs") {
    // Guards that the TastyIndexer itself produces references in a consistent order.
    // The ProjectIndex insertion-order guard (prepend-on-insert) lives in ProjectIndexSpec.
    for {
      refs1 <- compileAndIndex.map(_._2.values.flatMap(_._2).filter(_.referenceKind == ReferenceKind.Override).toList)
      refs2 <- compileAndIndex.map(_._2.values.flatMap(_._2).filter(_.referenceKind == ReferenceKind.Override).toList)
    } yield expect(refs1.map(_.symbol) == refs2.map(_.symbol))
  }
}
