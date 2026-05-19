package org.scala.abusers.sls.index

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.parsing.Parser
import dotty.tools.dotc.typer.TyperPhase
import dotty.tools.dotc.util.ClasspathFromClassloader
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver
import dotty.tools.unsupported

import java.io.File.pathSeparator

/** Receives the typed Java compilation units after Parser → TyperPhase. Implementations run with a live Context so they
  * can read symbols, types, and positions directly. Mirrors `scala.tasty.inspector.Inspector`.
  */
trait JavaInspector {
  def inspect(units: List[CompilationUnit])(using Context): Unit
}

/** Minimal dotc pipeline that runs Parser → TyperPhase on `.java` sources and hands the typed units to a
  * [[JavaInspector]] inside a capture phase. Mirrors the structure of [[TastyInspectorDriver]] but for Java input.
  */
object JavaFrontendDriver {

  /** Run the frontend on the given Java sources and invoke `inspector` once with all typed units. Returns true if no
    * errors were reported.
    */
  def inspect(javaFiles: List[String], extraClasspath: List[String] = Nil)(inspector: JavaInspector): Boolean = {

    class CapturePhase extends Phase {
      override def phaseName: String   = "captureJavaSymbols"
      override def isCheckable: Boolean = false

      override def runOn(units: List[CompilationUnit])(using ctx: Context): List[CompilationUnit] = {
        inspector.inspect(units)
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
    !reporter.hasErrors
  }
}
