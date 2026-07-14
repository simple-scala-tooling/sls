package org.scala.abusers.sls.util

import cats.effect.IO
import cats.syntax.all.*
import org.scala.abusers.sls.index.util.Code.*
import org.scala.abusers.sls.index.util.TestWorkspace
import org.scala.abusers.sls.SynchronizedState
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import weaver.*

import TestClient.awaitUntil

/** Queue-ordering guards (`ai/test-plan.md` 3.5 / 3.10), running against the real ComputationQueue (H1). */
object ConcurrencySpec extends SimpleIOSuite {

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  test("the classpath swap stays deferred until the next queued computation applies it (test-plan 3.5)") {
    val src = code"""
      |package example
      |
      |class Foo {
      |${m1}}
    """
    TestWorkspace.withSources("Foo.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      val uri      = h.workspace.uri("Foo.scala")
      val newClass =
        h.workspace.sourceRoot / os.up / ".sls" / "classes" / "sls-test-target" / "example" / "Foo.class"
      for {
        _ <- h.client.openFile(uri, src.text)
        _ <- h.client.insertAt(uri, src.at(m1), "  def marker: Int = 1\n")
        _ <- h.client.saveFile(uri)
        // marker indexed => the compile fiber ran updateOutputClasspath (queues the swap) before indexing
        _ <- awaitUntil(
          h.symbolIndex.getSymbolsByName("marker").map(s => Option.when(s.nonEmpty)(s)),
          label = "the save-triggered compile to finish and index",
        )
        stillDeferred <- IO.blocking(!os.exists(newClass))
        // any queued computation must drain the pending swap before it runs
        _       <- h.client.insertAt(uri, src.at(m1), "")
        applied <- IO.blocking(os.exists(newClass))
      } yield expect(
        stillDeferred,
        "the swap ran outside the synchronized queue — it can race in-flight reads of the classes dir",
      ) &&
        expect(applied, "the pending swap was not applied by the next queued computation")
    }
  }

  test("after a burst of incremental edits the server document equals the client buffer (test-plan 3.10)") {
    val src = code"""
      |package example
      |
      |class App {
      |${m1}}
    """
    TestWorkspace.withSources("App.scala" -> src.text).flatMap(TestServer.resource(_)).use { h =>
      given SynchronizedState = TestComputationQueue().unsafeGetState
      val uri                 = h.workspace.uri("App.scala")
      for {
        _ <- h.client.openFile(uri, src.text)
        _ <- (1 to 20).toList.traverse_ { i =>
          h.client.insertAt(uri, src.at(m1), s"  def gen$i: Int = $i\n")
        }
        clientBuffer <- h.client.bufferOf(uri)
        serverDoc    <- h.textDocumentSync.get(uri)
      } yield expect.same(clientBuffer, serverDoc.content)
    }
  }
}
