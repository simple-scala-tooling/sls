package org.scala.abusers
package zincCli

import cats.effect.*
import cats.effect.std.Mutex
import cats.syntax.all.*
import sbt.internal.inc.*
import sbt.internal.inc.javac.JavaCompiler
import sbt.internal.inc.javac.JavaTools
import sbt.internal.inc.javac.Javadoc
import xsbti.compile.AnalysisContents
import xsbti.compile.CompileAnalysis
import xsbti.compile.CompileOrder
import xsbti.compile.CompilerCache
import xsbti.compile.DefinesClass
import xsbti.compile.IncOptions
import xsbti.compile.MiniSetup
import xsbti.compile.PerClasspathEntryLookup
import xsbti.compile.PreviousResult
import xsbti.VirtualFile

import java.{util => ju}
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

case class ZincEntryLookup(analysis: Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def definesClass(classpathEntry: VirtualFile): DefinesClass = Locate.definesClass(classpathEntry)

  override def analysis(classpathEntry: VirtualFile): ju.Optional[CompileAnalysis] = analysis
}

class ZincCliServer(
    client: csp.CspClient[IO],
    compileLocks: Ref[IO, Map[String, Mutex[IO]]],
    // files we last published problems for, per scopeId — re-published empty once they compile clean
    lastReported: Ref[IO, Map[String, Set[String]]],
) extends csp.CspServer[IO] {

  // TODO(workaround): This per-scope lock just serializes compiles of the same target so they stop corrupting each
  // other's analysis store / output jar (`${scopeId}-analysis` / `${scopeId}-classes`, both keyed by scopeId). It is
  // NOT a proper solution: it never cancels a now-stale in-flight compile and never evicts dead keys from
  // `compileLocks`. The real fix is a per-target compile scheduler (cancel-previous or conflate). Rewrite once that
  // lands.

  /** One [[Mutex]] per scopeId. Compiles of the same target share an analysis store and output jar (both keyed by
    * scopeId), so they must run serially or they corrupt each other; distinct targets use distinct jars and stay
    * parallel.
    */
  private def lockFor(scopeId: String): IO[Mutex[IO]] =
    Mutex[IO].flatMap { fresh =>
      compileLocks.modify { locks =>
        locks.get(scopeId) match {
          case Some(existing) => (locks, existing)
          case None           => (locks.updated(scopeId, fresh), fresh)
        }
      }
    }

  def findFiles(sourcePath: Seq[Path]): IO[Seq[Path]] =
    IO.parSequence {
      sourcePath.map { path =>
        fs2.io.file
          .Files[IO]
          .walk(fs2.io.file.Path(path.toString))
          .filter(_.extName == ".scala")
          .compile
          .toList
      }
    }.map(_.flatten.map(_.toNioPath))

  val javaCompiler = JavaTools(
    JavaCompiler.local.getOrElse(sys.error("No java compiler")),
    Javadoc.local.getOrElse(sys.error("No java doc")),
  )
  val incrementalCompiler = IncrementalCompilerImpl()

  val outputDir = Files.createTempDirectory("zinc-classes-directory")

  Runtime.getRuntime.addShutdownHook(new Thread(() => { // to i tak trzeba poprawic bo nie ma analizy etc
    System.err.println(s"Deleting output directory $outputDir")
    sbt.io.IO.delete(outputDir.toFile)
  }))

  override def compile(
      scopeId: String,
      classpath: List[String],
      sourcePath: List[String],
      scalacOptions: List[String],
      javacOptions: List[String],
      scalaVersion: csp.ScalaVersion,
  ): IO[csp.CompileOutput] =
    lockFor(scopeId).flatMap(_.lock.surround(IO.defer {

      val sources0 = findFiles(sourcePath.map(Path.of(_)))
        .map(
          _.map(p => VirtualSourceFile(p, HashedContent.of(p)))
        ) // TODO: THIS WILL REHASH ALL SOURCES ON EVERY COMPILATION, LETS CACHE IT

      // FIXME: I'm bad
      val outputClassDir = outputDir.resolve("classes")
      if !Files.exists(outputClassDir) then Files.createDirectories(outputClassDir)

      val classpath0: List[PlainVirtualFile] = classpath.map(Path.of(_)).map(PlainVirtualFile.apply(_))

      val compilers0 =
        incrementalCompiler.compilers(javaCompiler, ScalaInstanceProvider.getScalaInstance(scalaVersion.version))

      val outputClassJar = OutputJarWithDirTemp(
        outputDir.resolve("classes"),
        outputDir.resolve("temp"),
        s"${scopeId}-classes",
      ) // + fingerprint

      val analysisJar =
        OutputJar(outputDir.resolve("analysis"), outputDir.resolve("temp"), s"${scopeId}-analysis") // + fingerprint
      val analysisStore = new ZincCliAnalysisStore(analysisJar)

      // val signatureAnalysisJar = OutputJar(outputDir, s"${scopeId}-signature-analysis")
      // val signatureAnalysisStore = new ZincCliAnalysisStore(signatureAnalysisJar)

      val previousResult = analysisStore
        .get()
        .map { analysisResult =>
          PreviousResult.create(analysisResult.getAnalysis(), analysisResult.getMiniSetup())
        }
        .orElse(PreviousResult.create(Optional.empty[CompileAnalysis](), Optional.empty[MiniSetup]()))

      // These three must stay in lock-step: best-effort scalac emits ".betasty" entries; we announce BETASTY format
      // downstream. Touch any of them only by touching all three.
      val extraScalacOptions = List("-Ybest-effort", "-Ywith-best-effort-tasty")
      val entrySuffix        = ".betasty"
      val outputFormat       = csp.OutputFormat.BETASTY

      val reporter = ZincCliReporter()

      val result = for {
        sources <- sources0
      } yield {
        System.err.println(s"sources: $sources")
        System.err.println(s"classpath: $classpath0")

        val fullClasspathMapped = classpath0.groupBy(_.toPath).map { (k, v) =>
          k -> v.head
        }

        val fileConverter = new ZincFileConverter(sources.map(s => s -> s).toMap, fullClasspathMapped)

        val incOptions = IncOptions
          .create()
          .withEnabled(true)
          .withPipelining(false)
          .withStoreApis(true)
          .withAllowMachinePath(false)
          .withUseCustomizedFileManager(true)

        val incrementalSetup = incrementalCompiler.setup(
          lookup = ZincEntryLookup(analysisStore.get().map(_.getAnalysis())),
          skip = false,
          cacheFile = analysisJar.path,
          cache = CompilerCache.fresh,
          incOptions = incOptions,
          reporter = reporter,
          progress = None,
          earlyAnalysisStore = None,
          extra = Array(),
        )

        val tempClassDir = outputDir.resolve("temp-class-dir")
        if !Files.exists(tempClassDir) then Files.createDirectories(tempClassDir)

        // val maybeSignatureAnalysisJar = if signatureAnalysisJar.path.toFile.exists() then Some(signatureAnalysisJar.path) else None

        val inputs = incrementalCompiler.inputs(
          classpath = classpath0.toArray,
          sources = sources.toArray,
          classesDirectory = outputClassJar.temp,
          earlyJarPath = None,
          // scalacOptions = Nil.toArray, // List("-Ystop-after:pickler", s"-Xearly-tasty-output:${classesDirectory0.resolve("tastyFiles.jar")}").toArray,
          scalacOptions = extraScalacOptions.toArray,
          javacOptions = javacOptions.toArray,
          maxErrors = Int.MaxValue,
          sourcePositionMappers = Array(),
          order = CompileOrder.Mixed,
          compilers = compilers0,
          setup = incrementalSetup,
          pr = previousResult,
          temporaryClassesDirectory = Optional.of(tempClassDir),
          converter = fileConverter,
          stampReader = ZincCliStamper(),
        )

        System.err.println(inputs)

        try {
          val compilationResult = incrementalCompiler.compile(inputs, ZincCliLogger())
          ZincCliLogger().info(() => compilationResult.toString())
          val changedFilesMap: Map[String, List[String]] =
            if compilationResult.hasModified then {
            val previousStamps: Map[xsbti.VirtualFileRef, xsbti.compile.analysis.Stamp] = {
              val prev = analysisStore.get()
              if prev.isPresent then prev.get().getAnalysis().asInstanceOf[Analysis].stamps.sources
              else Map.empty
            }

            val analysisContents = AnalysisContents.create(compilationResult.analysis(), compilationResult.setup())
            analysisStore.set(analysisContents)
            outputClassJar.cleanAndMoveToFinalDest
            val analysis      = compilationResult.analysis().asInstanceOf[Analysis]
            val currentStamps = analysis.stamps.sources

            val changedSources = currentStamps.filter { case (src, stamp) =>
              previousStamps.get(src) match {
                case Some(prevStamp) => prevStamp != stamp
                case None            => true // new source
              }
            }.keySet

            changedSources.map { src =>
              val classNames = analysis.relations.classNames(src)
              val entries    = classNames.toList.map(_.replace('.', '/') + entrySuffix)
              src.id -> entries
            }.toMap
          } else Map.empty
          (changedFilesMap, true)
        } catch {
          // Compile errors are part of the contract (success = false + diagnostics), not a transport error.
          case _: xsbti.CompileFailed => (Map.empty[String, List[String]], false)
        }
      }

      for {
        (changedFiles, completed) <- result
        // Under -Ybest-effort the compiler runs to completion (emitting betasty) despite errors, so the
        // reporter — not CompileFailed — distinguishes SUCCEEDED from BEST_EFFORT.
        status =
          if !completed then csp.CompilationStatus.FAILED
          else if reporter.hasErrors() then csp.CompilationStatus.BEST_EFFORT
          else csp.CompilationStatus.SUCCEEDED
        _ <- publishDiagnostics(scopeId, reporter.problems().toList)
      } yield csp.CompileOutput(outputClassJar.path.toString, changedFiles, outputFormat, status)
    }))

  /** Publish problems grouped per file; files reported last time that are clean now get an explicit empty publish so
    * the client can drop their stale diagnostics.
    */
  private def publishDiagnostics(scopeId: String, problems: List[xsbti.Problem]): IO[Unit] = {
    def optStr(o: Optional[String]): Option[String] = if o.isPresent then Some(o.get) else None
    val byFile: Map[String, List[csp.CompileDiagnostic]] =
      problems
        .flatMap(p => optStr(p.position.sourcePath).map(_ -> toDiagnostic(p)))
        .groupMap(_._1)(_._2)
    for {
      previous <- lastReported.modify(m => (m.updated(scopeId, byFile.keySet), m.getOrElse(scopeId, Set.empty)))
      _        <- byFile.toList.traverse_ { case (file, diags) =>
        client.onPublishDiagnostics(csp.PublishDiagnosticsParams(file, scopeId, diags))
      }
      _ <- (previous -- byFile.keySet).toList.traverse_ { file =>
        client.onPublishDiagnostics(csp.PublishDiagnosticsParams(file, scopeId, Nil))
      }
    } yield ()
  }

  private def toDiagnostic(p: xsbti.Problem): csp.CompileDiagnostic = {
    def opt(o: Optional[Integer]): Option[Int] = if o.isPresent then Some(o.get.intValue) else None
    val pos                                    = p.position
    // zinc lines are 1-based, columns 0-based; LSP wants both 0-based
    val range = opt(pos.startLine).orElse(opt(pos.line)).map { startLine =>
      val startChar = opt(pos.startColumn).orElse(opt(pos.pointer)).getOrElse(0)
      csp.DiagnosticRange(
        startLine = startLine - 1,
        startCharacter = startChar,
        endLine = opt(pos.endLine).map(_ - 1).getOrElse(startLine - 1),
        endCharacter = opt(pos.endColumn).getOrElse(startChar),
      )
    }
    val severity = p.severity match {
      case xsbti.Severity.Error => csp.DiagnosticSeverity.ERROR
      case xsbti.Severity.Warn  => csp.DiagnosticSeverity.WARNING
      case xsbti.Severity.Info  => csp.DiagnosticSeverity.INFO
    }
    csp.CompileDiagnostic(p.message, severity, range)
  }
}

object ZincCliServer {
  def create(client: csp.CspClient[IO]): IO[ZincCliServer] =
    for {
      locks    <- Ref.of[IO, Map[String, Mutex[IO]]](Map.empty)
      reported <- Ref.of[IO, Map[String, Set[String]]](Map.empty)
    } yield new ZincCliServer(client, locks, reported)
}
