package org.scala.abusers.sls.index
package util

import weaver.*

import Code.*
import IndexTestFixtures.mid
import IndexTestFixtures.tid

object TestWorkspaceSpec extends SimpleIOSuite {

  test("compiles a source and indexes its symbols under the workspace URI") {
    val src = code"""
      |package example
      |
      |class ${m1}Foo {
      |  def bar(x: Int): Int = x + 1
      |}
    """
    TestWorkspace.withSources("Foo.scala" -> src.text).use { ws =>
      for {
        idx  <- ws.symbolIndex
        foos <- idx.getSymbolsByName("Foo")
        bars <- idx.getSymbolsByName("bar")
      } yield {
        val foo = foos.find(_.id == tid("example.Foo"))
        expect(foo.isDefined) &&
        expect(bars.exists(_.id == mid("example.Foo.bar"))) &&
        expect(foo.exists(_.location.exists(_.uri == ws.uri("Foo.scala")))) &&
        expect(
          foo.exists(_.location.exists(l => l.startLine <= src.at(m1).line && src.at(m1).line <= l.endLine))
        )
      }
    }
  }

  test("multiple files are keyed to their own source URIs and linked across files") {
    TestWorkspace
      .withSources(
        "A.scala" -> "package example\n\ntrait A\n",
        "B.scala" -> "package example\n\nclass B extends A\n",
      )
      .use { ws =>
        for {
          idx      <- ws.symbolIndex
          as       <- idx.getSymbolsByName("A")
          bs       <- idx.getSymbolsByName("B")
          subtypes <- idx.getSubtypes(tid("example.A"))
        } yield expect(as.exists(_.location.exists(_.uri == ws.uri("A.scala")))) &&
          expect(bs.exists(_.location.exists(_.uri == ws.uri("B.scala")))) &&
          expect(subtypes.contains(tid("example.B")))
      }
  }

  test("indexing the same workspace twice produces identical symbol ids (determinism)") {
    val src = code"""
      |package example
      |
      |enum Color {
      |  case Red, Green
      |}
      |
      |object Color {
      |  def parse(s: String): Option[Color] = None
      |}
    """
    TestWorkspace.withSources("Color.scala" -> src.text).use { ws =>
      for {
        first  <- ws.indexResults
        second <- ws.indexResults
      } yield {
        val firstIds  = first.view.mapValues(_._1.map(_.id).toSet).toMap
        val secondIds = second.view.mapValues(_._1.map(_.id).toSet).toMap
        expect.same(firstIds, secondIds) && expect(firstIds.values.exists(_.nonEmpty))
      }
    }
  }

  test("compilation failure fails workspace acquisition") {
    TestWorkspace
      .withSources("Broken.scala" -> "package example\n\nclass Broken { val x: Int = \"nope\" }\n")
      .use(_ => cats.effect.IO.pure(success))
      .attempt
      .map(result => expect(result.isLeft))
  }
}
