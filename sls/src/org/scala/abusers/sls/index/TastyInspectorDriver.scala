package org.scala.abusers.sls.index

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.fromtasty.ReadTasty
import dotty.tools.dotc.fromtasty.TASTYCompiler
import dotty.tools.dotc.fromtasty.TASTYRun
import dotty.tools.dotc.interfaces
import dotty.tools.dotc.quoted.QuotesCache
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.util.ClasspathFromClassloader
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver
import dotty.tools.dotc.Run
import dotty.tools.unsupported

import java.io.File.pathSeparator
import scala.quoted.runtime.impl.QuotesImpl
import scala.tasty.inspector.Inspector
import scala.tasty.inspector.Tasty

/** A diagnostic dotc emitted while reading TASTy: an error or warning with a rendered, position-prefixed message. */
final case class IndexDiagnostic(isError: Boolean, message: String)

/** A low-level TASTy inspection driver that bypasses [[scala.tasty.inspector.TastyInspector]] to allow reading both
  * regular `.tasty` files and `.betasty` files produced by best-effort compilation (via `-Ywith-best-effort-tasty`).
  *
  * Uses an internal [[dotty.tools.dotc.fromtasty.TASTYCompiler]] pipeline: `ReadTasty` (frontend) followed by a custom
  * phase that invokes the [[scala.tasty.inspector.Inspector]].
  */
object TastyInspectorDriver {

  /** Returns the diagnostics dotc emitted while reading the TASTy (errors/warnings), captured rather than printed.
    * Empty means a clean read. A non-empty list does **not** mean nothing was indexed — the inspector collects what it
    * can past per-file errors; the diagnostics tell the caller the result is partial/degraded.
    */
  def inspectTastyFiles(
      tastyFiles: List[String],
      jars: List[String],
      dependenciesClasspath: List[String],
  )(inspector: Inspector): List[IndexDiagnostic] =
    inspect(tastyFiles ::: jars, dependenciesClasspath, bestEffort = false)(inspector)

  def inspectBetastyFiles(
      betastyFiles: List[String],
      jars: List[String],
      dependenciesClasspath: List[String],
  )(inspector: Inspector): List[IndexDiagnostic] =
    inspect(betastyFiles ::: jars, dependenciesClasspath, bestEffort = true)(inspector)

  private def inspect(files: List[String], dependenciesClasspath: List[String], bestEffort: Boolean)(
      inspector: Inspector
  ): List[IndexDiagnostic] =
    files match {
      case Nil => Nil
      case _   =>
        val reporter = new CollectingReporter
        inspectorDriver(inspector).process(inspectorArgs(dependenciesClasspath, files, bestEffort), reporter, null)
        reporter.collected
    }

  /** A dotc [[Reporter]] that captures diagnostics (rendering message + position while the compiler context is still
    * alive) instead of printing them to stderr — both to stop the red spew corrupting the user's view and to hand the
    * structured errors back to the indexer.
    */
  private class CollectingReporter extends Reporter {
    private val buf = scala.collection.mutable.ListBuffer.empty[IndexDiagnostic]

    override def doReport(dia: Diagnostic)(using Context): Unit = {
      val where = if dia.pos.exists then s"${dia.pos.source.name}:${dia.pos.startLine + 1}: " else ""
      buf += IndexDiagnostic(isError = dia.level >= interfaces.Diagnostic.ERROR, message = where + dia.message)
    }

    def collected: List[IndexDiagnostic] = buf.toList
  }

  private def inspectorDriver(inspector: Inspector) = {
    class InspectorDriver extends Driver {
      override protected def newCompiler(implicit ctx: Context): Compiler = new TastyFromClass
    }

    class TastyInspectorPhase extends Phase {
      override def phaseName: String = "tastyInspector"

      override def runOn(units: List[CompilationUnit])(using ctx0: Context): List[CompilationUnit] = {
        val ctx        = QuotesCache.init(ctx0.fresh)
        val quotesImpl = QuotesImpl()(using ctx)
        class TastyImpl(val path: String, val ast: quotesImpl.reflect.Tree) extends Tasty[quotesImpl.type] {
          val quotes = quotesImpl
        }
        val tastys =
          units.map(unit => new TastyImpl(unit.source.path, unit.tpdTree.asInstanceOf[quotesImpl.reflect.Tree]))
        inspector.inspect(using quotesImpl)(tastys)
        units
      }

      override def run(implicit ctx: Context): Unit = unsupported("run")
    } // end TastyInspectorPhase

    class TastyFromClass extends TASTYCompiler {
      override protected def frontendPhases: List[List[Phase]] =
        List(new ReadTasty) :: Nil
      override protected def picklerPhases: List[List[Phase]]   = Nil
      override protected def transformPhases: List[List[Phase]] = Nil
      override protected def backendPhases: List[List[Phase]]   =
        List(new TastyInspectorPhase) :: Nil

      override def newRun(implicit ctx: Context): Run = {
        reset()
        val ctx2 = ctx.fresh
          .addMode(Mode.ReadPositions)
          .setSetting(ctx.settings.XreadComments, true)
        new TASTYRun(this, ctx2)
      }
    }

    new InspectorDriver
  }

  private def inspectorArgs(classpath: List[String], classes: List[String], bestEffort: Boolean): Array[String] = {
    val currentClasspath = ClasspathFromClassloader(getClass.getClassLoader)
    val fullClasspath    = (classpath :+ currentClasspath).mkString(pathSeparator)
    val extraFlags       = if bestEffort then List("-Ywith-best-effort-tasty") else Nil
    ("-from-tasty" :: "-Yretain-trees" :: extraFlags ::: "-classpath" :: fullClasspath :: classes).toArray
  }

} // end TastyInspectorDriver
