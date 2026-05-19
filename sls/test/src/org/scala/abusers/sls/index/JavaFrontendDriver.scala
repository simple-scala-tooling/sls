package org.scala.abusers.sls.index

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.parsing.Parser
import dotty.tools.dotc.typer.TyperPhase
import dotty.tools.dotc.util.ClasspathFromClassloader
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver
import dotty.tools.unsupported

import java.io.File.pathSeparator
import scala.collection.mutable

final case class JavaMethodInfo(name: String, signature: String)
final case class JavaFieldInfo(name: String, tpe: String)
final case class JavaClassInfo(fullName: String, methods: List[JavaMethodInfo], fields: List[JavaFieldInfo])
final case class JavaFrontendResult(classes: List[JavaClassInfo], hasErrors: Boolean)

/** Minimal dotc pipeline that runs Parser → TyperPhase on `.java` sources and snapshots the class/member symbols that
  * the frontend produces. Mirrors the structure of [[TastyInspectorDriver]] but for Java source input.
  */
object JavaFrontendDriver {

  def runOnJavaSources(javaFiles: List[String], extraClasspath: List[String] = Nil): JavaFrontendResult = {
    val collector = mutable.ListBuffer.empty[JavaClassInfo]

    class CapturePhase extends Phase {
      override def phaseName: String   = "captureJavaSymbols"
      override def isCheckable: Boolean = false

      override def runOn(units: List[CompilationUnit])(using ctx: Context): List[CompilationUnit] = {
        import dotty.tools.dotc.ast.tpd
        import dotty.tools.dotc.ast.tpd.TreeOps
        units.foreach { unit =>
          unit.tpdTree.foreachSubTree {
            case td: tpd.TypeDef if td.symbol.isClass =>
              val cls   = td.symbol.asClass
              val decls = cls.info.decls.toList
              val methods = decls.collect {
                case m if m.is(Flags.Method) && !m.isClassConstructor =>
                  JavaMethodInfo(m.name.toString, m.info.show)
              }
              val fields = decls.collect {
                case f if f.isTerm && !f.is(Flags.Method) =>
                  JavaFieldInfo(f.name.toString, f.info.show)
              }
              collector += JavaClassInfo(cls.fullName.toString, methods, fields)
            case _ => ()
          }
        }
        units
      }

      override def run(using Context): Unit = unsupported("run")
    }

    class JavaFrontendCompiler extends Compiler {
      override protected def frontendPhases: List[List[Phase]] =
        List(new Parser) :: List(new TyperPhase) :: Nil
      override protected def picklerPhases: List[List[Phase]]   = Nil
      override protected def transformPhases: List[List[Phase]] = Nil
      override protected def backendPhases: List[List[Phase]]   = List(new CapturePhase) :: Nil
    }

    class JavaFrontendDriverImpl extends Driver {
      override protected def newCompiler(implicit ctx: Context): Compiler = new JavaFrontendCompiler
    }

    val currentCp = ClasspathFromClassloader(getClass.getClassLoader)
    val fullCp    = (extraClasspath :+ currentCp).mkString(pathSeparator)
    // -Xjava-tasty prevents TyperPhase from dropping Java units after typing (default behavior keeps Java around
    // only long enough to feed Scala typing). With it on, the typed Java unit survives into later phases — which
    // is what our CapturePhase needs.
    val args     = Array("-color:never", "-Xjava-tasty", "-classpath", fullCp) ++ javaFiles
    val reporter = (new JavaFrontendDriverImpl).process(args)
    JavaFrontendResult(collector.toList, reporter.hasErrors)
  }
}
