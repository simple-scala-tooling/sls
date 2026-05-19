package org.scala.abusers.sls.index

import cats.effect.IO
import weaver.*

import java.nio.file.Paths

object JavaFrontendSpec extends SimpleIOSuite {

  private def resourcePath(name: String): String = {
    val url = Option(getClass.getResource(s"/java-frontend/$name"))
      .getOrElse(sys.error(s"missing test resource: /java-frontend/$name"))
    Paths.get(url.toURI).toString
  }

  private def runFrontend: IO[JavaFrontendResult] = IO.blocking {
    JavaFrontendDriver.runOnJavaSources(List(resourcePath("Greeter.java")))
  }

  test("frontend completes without errors on a Java source") {
    runFrontend.map(r => expect(!r.hasErrors))
  }

  test("Namer enters the Java class symbol — visible after Typer") {
    runFrontend.map(r => expect(r.classes.exists(_.fullName == "fixture.Greeter")))
  }

  test("Typer resolves Java method signature (param + return types)") {
    runFrontend.map { r =>
      val greet = r.classes.flatMap(_.methods).find(_.name == "greet")
      expect(greet.isDefined) and
        expect(greet.exists(_.signature.contains("String")))
    }
  }

  test("Typer resolves Java field type (primitive int → Int)") {
    runFrontend.map { r =>
      val counter = r.classes.flatMap(_.fields).find(_.name == "counter")
      expect(counter.isDefined) and
        expect(counter.exists(_.tpe.contains("Int")))
    }
  }
}
