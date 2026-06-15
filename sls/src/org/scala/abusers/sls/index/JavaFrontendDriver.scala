package org.scala.abusers.sls.index

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.parsing.Parser
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.typer.TyperPhase
import dotty.tools.dotc.util.ClasspathFromClassloader
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver
import dotty.tools.io.AbstractFile
import dotty.tools.unsupported

import java.io.File.pathSeparator
import java.nio.file.Path
import scala.io.Codec

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

  /** Run the frontend on the given Java source paths and invoke `inspector` once with all typed units. */
  def inspect(javaFiles: List[String], extraClasspath: List[String] = Nil)(inspector: JavaInspector): Boolean =
    inspectSources(javaFiles.map(p => SourceFile(AbstractFile.getFile(Path.of(p)), Codec.UTF8)), extraClasspath)(
      inspector
    )

  /** Run the frontend on caller-provided [[SourceFile]]s. Lets the caller back sources with a [[ZipPath]] (or any other
    * `java.nio.file.Path`) so we can read Java from inside a sources jar without extracting it.
    */
  def inspectSources(sources: List[SourceFile], extraClasspath: List[String])(inspector: JavaInspector): Boolean = {

    class CapturePhase extends Phase {
      override def phaseName: String    = "captureJavaSymbols"
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
      // We feed sources directly to Run.compileSources rather than passing them as positional args, so the Driver
      // must not bail out at setup time for "no source files given".
      override protected def sourcesRequired: Boolean = false

      override protected def newCompiler(implicit ctx: Context): Compiler = new JavaFrontendCompiler

      def runOn(srcs: List[SourceFile], args: Array[String]): Boolean =
        setup(args, initCtx) match {
          case None           => true
          case Some((_, ctx)) =>
            // Swap in a StoreReporter so dotc diagnostics from outline-typing dependency/JDK Java sources (unresolved
            // annotations like org.jspecify, JDK src.zip quirks, etc.) are buffered instead of printed. The default
            // ConsoleReporter echoes to System.out — which here is the LSP JSON-RPC pipe, so its output corrupts the
            // framing and crashes the client — and to System.err, which clutters the client log. CapturePhase still
            // runs and harvests symbols regardless of these errors.
            given Context = ctx.fresh.setReporter(new StoreReporter(null, fromTyperState = false))
            try {
              val run = newCompiler.newRun
              run.compileSources(srcs)
            } catch {
              case _: InterruptedException => ()
            }
            !summon[Context].reporter.hasErrors
        }
    }

    val currentCp = ClasspathFromClassloader(getClass.getClassLoader)
    val fullCp    = (extraClasspath :+ currentCp).mkString(pathSeparator)
    // -Xjava-tasty prevents TyperPhase from dropping Java units after typing (default behavior keeps Java around
    // only long enough to feed Scala typing). With it on, the typed Java unit survives into later phases — which
    // is what our CapturePhase needs.
    val args = Array("-color:never", "-Xjava-tasty", "-classpath", fullCp)
    (new JavaFrontendDriverImpl).runOn(sources, args)
  }
}
