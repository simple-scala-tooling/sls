package org.scala.abusers.sls.util

import cats.effect.IO
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import TestClient.awaitUntil

object TestClientSpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  test("open, edit, save flows through compile into the index") {
    val src = code"""
      |package example
      |
      |class Foo {
      |  def bar(x: Int): Int = x + 1
      |${m1}}
    """
    TestWorkspace.withSources("Foo.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      val uri = h.workspace.uri("Foo.scala")
      for {
        _          <- h.client.openFile(uri, src.text)
        _          <- h.client.insertAt(uri, src.at(m1), "  def baz: String = \"b\"\n")
        _          <- h.client.saveFile(uri)
        bazSymbols <- awaitUntil(
          h.symbolIndex.getSymbolsByName("baz").map(s => Option.when(s.nonEmpty)(s)),
          label = "baz to appear in the index after save",
        )
        barSymbols <- h.symbolIndex.getSymbolsByName("bar")
      } yield expect(bazSymbols.exists(_.location.exists(_.uri == uri))) &&
        expect(barSymbols.nonEmpty)
    }
  }

  test("determinism: the index after an editing session matches a fresh index of the final content") {
    val src = code"""
      |package example
      |
      |class Foo {
      |  def bar(x: Int): Int = x + 1
      |${m1}}
    """
    TestWorkspace.withSources("Foo.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      val uri = h.workspace.uri("Foo.scala")
      for {
        _ <- h.client.openFile(uri, src.text)
        _ <- h.client.insertAt(uri, src.at(m1), "  def baz(s: String): String = s\n")
        _ <- h.client.saveFile(uri)
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("baz").map(s => Option.when(s.nonEmpty)(s)),
          label = "save-driven index update",
        )
        // empty-prefix search returns every indexed symbol; the project tier is this one file
        sessionIds   <- h.symbolIndex.searchSymbols("").map(_.map(_.id).toSet)
        finalContent <- h.client.bufferOf(uri)
        freshIds     <- TestWorkspace
          .withSources("Foo.scala" -> finalContent)
          .use(_.symbolIndex.flatMap(_.searchSymbols("").map(_.map(_.id).toSet)))
      } yield expect.same(freshIds, sessionIds)
    }
  }

  test("incremental edits keep the client buffer consistent with what gets saved") {
    val src = code"""
      |package example
      |
      |object App {
      |  val ${m1}greeting${m2} = "hi"
      |}
    """
    TestWorkspace.withSources("App.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      val uri = h.workspace.uri("App.scala")
      for {
        _      <- h.client.openFile(uri, src.text)
        _      <- h.client.editFile(uri, lsp.Range(src.at(m1), src.at(m2)), "farewell")
        buffer <- h.client.bufferOf(uri)
        _      <- h.client.saveFile(uri)
        onDisk <- IO.blocking(os.read(h.workspace.sources("App.scala")))
      } yield expect(buffer.contains("val farewell = \"hi\"")) &&
        expect.same(buffer, onDisk)
    }
  }
}
