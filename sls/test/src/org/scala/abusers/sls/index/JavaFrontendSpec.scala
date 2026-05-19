package org.scala.abusers.sls.index

import cats.effect.IO
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.CompilationUnit
import weaver.*

import java.nio.file.Paths
import scala.collection.mutable

object JavaFrontendSpec extends SimpleIOSuite {

  private def resourcePath(name: String): String = {
    val url = Option(getClass.getResource(s"/java-frontend/$name"))
      .getOrElse(sys.error(s"missing test resource: /java-frontend/$name"))
    Paths.get(url.toURI).toString
  }

  private case class Captured(classFullName: String, methodNames: List[String], fieldNames: List[String])

  private def runFrontend: IO[(Boolean, List[Captured])] = IO.blocking {
    val collected = mutable.ListBuffer.empty[Captured]
    val inspector = new JavaInspector {
      def inspect(units: List[CompilationUnit])(using Context): Unit = {
        import dotty.tools.dotc.ast.tpd
        import dotty.tools.dotc.ast.tpd.TreeOps
        units.foreach { unit =>
          unit.tpdTree.foreachSubTree {
            case td: tpd.TypeDef if td.symbol.isClass =>
              val cls   = td.symbol.asClass
              val decls = cls.info.decls.toList
              val methods = decls.collect {
                case m if m.is(Flags.Method) && !m.isClassConstructor => m.name.toString
              }
              val fields = decls.collect {
                case f if f.isTerm && !f.is(Flags.Method) => f.name.toString
              }
              collected += Captured(cls.fullName.toString, methods, fields)
            case _ => ()
          }
        }
      }
    }
    val ok = JavaFrontendDriver.inspect(List(resourcePath("Greeter.java")))(inspector)
    (ok, collected.toList)
  }

  test("frontend completes without errors on a Java source") {
    runFrontend.map { case (ok, _) => expect(ok) }
  }

  test("Namer enters the Java class symbol — visible after Typer") {
    runFrontend.map { case (_, captured) =>
      expect(captured.exists(_.classFullName == "fixture.Greeter"))
    }
  }

  test("Typer surfaces Java method names through the symbol table") {
    runFrontend.map { case (_, captured) =>
      val greeter = captured.find(_.classFullName == "fixture.Greeter")
      expect(greeter.exists(_.methodNames.contains("greet")))
    }
  }

  test("Typer surfaces Java field names through the symbol table") {
    runFrontend.map { case (_, captured) =>
      val greeter = captured.find(_.classFullName == "fixture.Greeter")
      expect(greeter.exists(_.fieldNames.contains("counter")))
    }
  }
}
