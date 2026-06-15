package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.SourceUri
import org.typelevel.otel4s.trace.Tracer
import weaver.*

/** Indexes the pre-compiled `tastyIndexerFixture` JAR published to `~/.m2` by `sls.test.forkArgs` — see
  * `IndexTestFixtures`. No dotc at test time.
  */
object TastyIndexerSpec extends SimpleIOSuite {

  private given Tracer[IO] = Tracer.noop[IO]

  private def indexFixture: IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    TastyIndexer("test-target").indexJar(IndexTestFixtures.tastyIndexerJar, Nil)

  private lazy val indexed: IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    indexFixture.memoize.flatten

  private def allSymbols: IO[List[IndexedSymbol]] =
    indexed.map(_.values.flatMap(_._1).toList)

  private def allRefs: IO[List[SymbolReference]] =
    indexed.map(_.values.flatMap(_._2).toList)

  private def findById(id: SymbolId): IO[Option[IndexedSymbol]] =
    allSymbols.map(_.find(_.id == id))

  test("simple class with method — both symbols extracted") {
    for {
      cls    <- findById(IndexTestFixtures.tid("fixture.SimpleClass"))
      method <- findById(IndexTestFixtures.mid("fixture.SimpleClass.greet"))
    } yield expect(cls.isDefined) and
      expect(cls.exists(_.kind == SymbolKind.Class)) and
      expect(method.isDefined) and
      expect(method.exists(_.kind == SymbolKind.Method))
  }

  test("trait with abstract method — Trait kind") {
    for {
      sym <- findById(IndexTestFixtures.tid("fixture.Animal"))
    } yield expect(sym.exists(_.kind == SymbolKind.Trait))
  }

  test("case class — class extracted") {
    for {
      sym <- findById(IndexTestFixtures.tid("fixture.Point"))
    } yield expect(sym.exists(_.kind == SymbolKind.Class))
  }

  test("enum with cases — Enum kind and EnumCase kind for each case") {
    for {
      sym  <- findById(IndexTestFixtures.tid("fixture.Color"))
      syms <- allSymbols
      red   = syms.find(s => s.name == "Red" && s.kind == SymbolKind.EnumCase)
      green = syms.find(s => s.name == "Green" && s.kind == SymbolKind.EnumCase)
      blue  = syms.find(s => s.name == "Blue" && s.kind == SymbolKind.EnumCase)
    } yield expect(sym.exists(_.kind == SymbolKind.Enum)) and
      expect(red.isDefined) and expect(green.isDefined) and expect(blue.isDefined)
  }

  test("sealed trait children — parent/child relationships") {
    for {
      circle <- findById(IndexTestFixtures.tid("fixture.Circle"))
      rect   <- findById(IndexTestFixtures.tid("fixture.Rectangle"))
    } yield expect(circle.exists(_.parents.contains(IndexTestFixtures.tid("fixture.Shape")))) and
      expect(rect.exists(_.parents.contains(IndexTestFixtures.tid("fixture.Shape"))))
  }

  test("class extending trait — parents list correct") {
    for {
      sym <- findById(IndexTestFixtures.tid("fixture.FriendlyGreeter"))
    } yield expect(sym.exists(_.parents.contains(IndexTestFixtures.tid("fixture.Greeter"))))
  }

  test("method override — Override reference points to overridden symbol") {
    for {
      refs <- allRefs
      overrideRefs = refs.filter(_.referenceKind == ReferenceKind.Override)
    } yield expect(overrideRefs.exists(_.symbol == IndexTestFixtures.mid("fixture.Greeter.greet")))
  }

  test("multiple top-level defs — all keyed to same source URI") {
    for {
      result <- indexed
      multiUri = result.find { case (_, (syms, _)) =>
        syms.exists(_.id == IndexTestFixtures.tid("fixture.TopA")) && syms.exists(
          _.id == IndexTestFixtures.tid("fixture.TopB")
        )
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
      secret <- findById(IndexTestFixtures.mid("fixture.VisibilityExample.secret"))
      helper <- findById(IndexTestFixtures.mid("fixture.VisibilityExample.helper"))
      pub    <- findById(IndexTestFixtures.mid("fixture.VisibilityExample.publicMethod"))
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
      first  <- indexFixture
      second <- indexFixture
      firstIds  = first.values.flatMap(_._1).map(_.id).toSet
      secondIds = second.values.flatMap(_._1).map(_.id).toSet
    } yield expect(firstIds == secondIds)
  }

  test("TastyIndexer reference output is stable across runs") {
    // Guards that the TastyIndexer itself produces references in a consistent order.
    // The ProjectIndex insertion-order guard (prepend-on-insert) lives in ProjectIndexSpec.
    for {
      refs1 <- indexFixture.map(_.values.flatMap(_._2).filter(_.referenceKind == ReferenceKind.Override).toList)
      refs2 <- indexFixture.map(_.values.flatMap(_._2).filter(_.referenceKind == ReferenceKind.Override).toList)
    } yield expect(refs1.map(_.symbol) == refs2.map(_.symbol))
  }
}
