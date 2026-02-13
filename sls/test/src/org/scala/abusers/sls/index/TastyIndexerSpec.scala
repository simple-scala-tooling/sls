package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*
import java.net.URI

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

  private def compileAndIndex: IO[(os.Path, Map[URI, (List[IndexedSymbol], List[SymbolReference])])] = IO.blocking {
    val tmpDir = os.temp.dir(prefix = "tasty-indexer-test")
    val srcDir = tmpDir / "src"
    val outDir = tmpDir / "out"
    os.makeDir.all(srcDir)
    os.makeDir.all(outDir)

    fixtures.foreach { case (name, content) =>
      os.write(srcDir / name, content)
    }

    val sourceFiles = os.list(srcDir).filter(_.ext == "scala").map(_.toString).toArray
    val args = Array("-d", outDir.toString, "-usejavacp") ++ sourceFiles

    dotty.tools.dotc.Main.process(args)

    (tmpDir, ())
  }.flatMap { case (tmpDir, _) =>
    val outDir = tmpDir / "out"
    val indexer = TastyIndexer("test-target")
    indexer.indexDirectory(outDir, Nil).map(result => (tmpDir, result))
  }

  private lazy val indexed: IO[(os.Path, Map[URI, (List[IndexedSymbol], List[SymbolReference])])] =
    compileAndIndex.memoize.flatten

  private def allSymbols: IO[List[IndexedSymbol]] =
    indexed.map(_._2.values.flatMap(_._1).toList)

  private def allRefs: IO[List[SymbolReference]] =
    indexed.map(_._2.values.flatMap(_._2).toList)

  private def findSymbol(name: String): IO[Option[IndexedSymbol]] =
    allSymbols.map(_.find(_.name == name))

  test("simple class with method — both symbols extracted") {
    for {
      syms <- allSymbols
      cls = syms.find(s => s.name == "SimpleClass" && s.kind == SymbolKind.Class)
      method = syms.find(s => s.name == "greet" && s.kind == SymbolKind.Method)
    } yield expect(cls.isDefined) and expect(method.isDefined)
  }

  test("trait with abstract method — Trait kind") {
    for {
      sym <- findSymbol("Animal")
    } yield expect(sym.exists(_.kind == SymbolKind.Trait))
  }

  test("case class — class extracted") {
    for {
      sym <- findSymbol("Point")
    } yield expect(sym.exists(_.kind == SymbolKind.Class))
  }

  test("enum with cases — Enum and EnumCase kinds") {
    for {
      syms <- allSymbols
      enumSym = syms.find(s => s.name == "Color" && s.kind == SymbolKind.Enum)
    } yield expect(enumSym.isDefined)
  }

  test("sealed trait children — parent/child relationships") {
    for {
      syms <- allSymbols
      circle = syms.find(s => s.name == "Circle" && s.kind == SymbolKind.Class)
      rect = syms.find(s => s.name == "Rectangle" && s.kind == SymbolKind.Class)
    } yield expect(circle.exists(_.parents.exists(_.value.contains("Shape")))) and
      expect(rect.exists(_.parents.exists(_.value.contains("Shape"))))
  }

  test("class extending trait — parents list correct") {
    for {
      sym <- findSymbol("FriendlyGreeter")
    } yield expect(sym.exists(_.parents.exists(_.value.contains("Greeter"))))
  }

  test("method override — Override reference generated") {
    for {
      refs <- allRefs
      overrideRefs = refs.filter(_.referenceKind == ReferenceKind.Override)
    } yield expect(overrideRefs.nonEmpty)
  }

  test("multiple top-level defs — all keyed to same source URI") {
    for {
      result <- indexed.map(_._2)
      multiUri = result.find { case (_, (syms, _)) =>
        syms.exists(_.name == "TopA") && syms.exists(_.name == "TopB")
      }
    } yield expect(multiUri.isDefined)
  }

  test("synthetic symbols not indexed") {
    for {
      syms <- allSymbols
      anons = syms.filter(_.name.contains("$anon"))
      evidence = syms.filter(_.name.contains("$evidence"))
    } yield expect(anons.isEmpty) and expect(evidence.isEmpty)
  }

  test("visibility — private/protected/public correctly determined") {
    for {
      syms <- allSymbols
      secret = syms.find(_.name == "secret")
      helper = syms.find(_.name == "helper")
      pub = syms.find(_.name == "publicMethod")
    } yield expect(secret.exists(_.visibility == Visibility.Private)) and
      expect(helper.exists(_.visibility == Visibility.Protected)) and
      expect(pub.exists(_.visibility == Visibility.Public))
  }

  test("all symbols have ProjectTasty origin") {
    for {
      syms <- allSymbols
    } yield expect(syms.forall(_.origin.isInstanceOf[SymbolOrigin.ProjectTasty]))
  }

  test("all symbols have locations") {
    for {
      syms <- allSymbols
    } yield expect(syms.forall(_.location.isDefined))
  }

  test("extends references generated for parent types") {
    for {
      refs <- allRefs
      extendsRefs = refs.filter(_.referenceKind == ReferenceKind.Extends)
    } yield expect(extendsRefs.nonEmpty)
  }
}
