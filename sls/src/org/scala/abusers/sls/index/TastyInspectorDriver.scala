package org.scala.abusers.sls.index

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.fromtasty.ReadTasty
import dotty.tools.dotc.fromtasty.TASTYCompiler
import dotty.tools.dotc.fromtasty.TASTYRun
import dotty.tools.dotc.quoted.QuotesCache
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

/** A low-level TASTy inspection driver that bypasses [[scala.tasty.inspector.TastyInspector]] to allow reading both
  * regular `.tasty` files and `.betasty` files produced by best-effort compilation (via `-Ywith-best-effort-tasty`).
  *
  * Uses an internal [[dotty.tools.dotc.fromtasty.TASTYCompiler]] pipeline: `ReadTasty` (frontend) followed by a custom
  * phase that invokes the [[scala.tasty.inspector.Inspector]].
  */
object TastyInspectorDriver {

  def inspectTastyFiles(
      tastyFiles: List[String],
      jars: List[String],
      dependenciesClasspath: List[String],
  )(inspector: Inspector): Boolean =
    inspect(tastyFiles ::: jars, dependenciesClasspath, bestEffort = false)(inspector)

  def inspectBetastyFiles(
      betastyFiles: List[String],
      jars: List[String],
      dependenciesClasspath: List[String],
  )(inspector: Inspector): Boolean =
    inspect(betastyFiles ::: jars, dependenciesClasspath, bestEffort = true)(inspector)

  private def inspect(files: List[String], dependenciesClasspath: List[String], bestEffort: Boolean)(
      inspector: Inspector
  ): Boolean =
    files match {
      case Nil => true
      case _   =>
        val reporter = inspectorDriver(inspector).process(inspectorArgs(dependenciesClasspath, files, bestEffort))
        !reporter.hasErrors
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
