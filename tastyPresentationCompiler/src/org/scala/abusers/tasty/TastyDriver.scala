package org.scala.abusers.tasty

import scala.quoted._
import scala.quoted.runtime.impl.QuotesImpl

import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver
import dotty.tools.dotc.Run
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.fromtasty._
import dotty.tools.dotc.quoted.QuotesCache
import dotty.tools.dotc.util.ClasspathFromClassloader
import dotty.tools.dotc.CompilationUnit
import dotty.tools.unsupported
import dotty.tools.dotc.report

import java.io.File.pathSeparator
import java.net.URI
import scala.compiletime.uninitialized
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.reporting.UniqueMessagePositions
import dotty.tools.dotc.reporting.HideNonSensicalMessages
import dotty.tools.io.AbstractFile
import dotty.tools.FatalError
import dotty.tools.io.JarArchive

class TastyDriver extends Driver {
  val compiler = new TastyFromClass

  override protected def sourcesRequired: Boolean = false

  val myInitCtx: Context = {
    val rootCtx = initCtx.fresh
      .addMode(Mode.ReadPositions)
    rootCtx
  }

  var myCtx = myInitCtx

  def run(settings: Array[String], tastyFile: AbstractFile): List[Diagnostic] = {
    import dotty.tools.dotc.typer.ImportInfo.withRootImports

    val previousCtx = myCtx
    try {
      val reporter = new StoreReporter(null) with UniqueMessagePositions with HideNonSensicalMessages
      val ctx = setup(settings, myInitCtx) match {
        case Some(_, ctx) => ctx
        case None => myInitCtx
      }
      ctx.initialize()(using ctx)

      val run = compiler.newRun(using ctx.fresh.setReporter(reporter))
      myCtx = run.runContext
      given Context = myCtx

      run.compile(List(tastyFile))

      println(run.units)
      println(ctx.compilationUnit)

      val ctxRun = run.runContext.compilationUnit
      println(ctxRun)
      // val unit = if ctxRun.units.nonEmpty then ctxRun.units.head else ctxRun.suspendedUnits.head
      reporter.removeBufferedMessages
    } catch {
      case ex: FatalError =>
        myCtx = previousCtx
        Nil
    }
  }

}

  // class TastyInspectorPhase extends Phase {
  //   override def phaseName: String = "tastyInspector"

  //   override def runOn(units: List[CompilationUnit])(using ctx0: Context): List[CompilationUnit] =
  //     // NOTE: although this is a phase, do not expect this to be ran with an xsbti.CompileProgress
  //     val ctx = QuotesCache.init(ctx0.fresh)
  //     runOnImpl(units)(using ctx)

  //   private def runOnImpl(units: List[CompilationUnit])(using Context): List[CompilationUnit] =
  //     val quotesImpl = QuotesImpl()
  //     class TastyImpl(val path: String, val ast: quotesImpl.reflect.Tree) extends Tasty[quotesImpl.type] {
  //       val quotes = quotesImpl
  //     }
  //     val tastys = units.map(unit => new TastyImpl(unit.source.path , unit.tpdTree.asInstanceOf[quotesImpl.reflect.Tree]))
  //     inspector.inspect(using quotesImpl)(tastys)
  //     units

  //   override def run(implicit ctx: Context): Unit = unsupported("run")
  // }

class TastyFromClass extends TASTYCompiler {

  override protected def frontendPhases: List[List[Phase]] =
    List(new ReadTasty) :: // Load classes from tasty
    Nil

  override protected def picklerPhases: List[List[Phase]] = Nil

  override protected def transformPhases: List[List[Phase]] = Nil

  override protected def backendPhases: List[List[Phase]] =
    // List(new TastyInspectorPhase) ::  // Perform a callback for each compilation unit
    Nil


  override def newRun(implicit ctx: Context): Run = {
    reset()
    val ctx2 = ctx.fresh
    new TASTYRun(this, ctx2)

  }
}

class CachingTastyDriver extends TastyDriver {

  private var lastCompilerURI: String = uninitialized
  private var lastDiags: List[Diagnostic] = Nil

  def loadTasty(tastyFile: AbstractFile, classpath: List[String]): List[Diagnostic] = {
    if lastCompilerURI == tastyFile.path then lastDiags
    else {
      val diags = run(inspectorArgs(classpath), tastyFile)
      lastCompilerURI = tastyFile.path
      lastDiags = diags
      diags
    }
  }

  private def inspectorArgs(classpath: List[String]): Array[String] = {
    val currentClasspath = ClasspathFromClassloader(getClass.getClassLoader)
    val fullClasspath = (classpath :+ currentClasspath).mkString(pathSeparator)
    ("-from-tasty" :: "-Yretain-trees" :: "-classpath" :: fullClasspath :: Nil).toArray
  }
}
