package org.scala.abusers
package zincCli

import cats.effect.*
import sbt.internal.inc.*
import xsbti.VirtualFile
import java.nio.file.Path
import java.nio.file.Files
import xsbti.compile.CompileOrder
import sbt.internal.inc.javac.JavaCompiler
import sbt.internal.inc.javac.JavaTools
import sbt.internal.inc.javac.Javadoc
import java.util.Optional
import xsbti.compile.PreviousResult
import xsbti.compile.CompileAnalysis
import xsbti.compile.MiniSetup
import java.{util => ju}
import xsbti.compile.IncOptions
import xsbti.compile.CompilerCache
import xsbti.compile.PerClasspathEntryLookup
import xsbti.compile.DefinesClass
import xsbti.compile.AnalysisContents

case class ZincEntryLookup(analysis: Optional[CompileAnalysis]) extends PerClasspathEntryLookup {
  override def definesClass(classpathEntry: VirtualFile): DefinesClass = Locate.definesClass(classpathEntry)

  override def analysis(classpathEntry: VirtualFile): ju.Optional[CompileAnalysis] = analysis
}


class ZincCliServer extends csp.CspServer[IO] {

  def findFiles(sourcePath: Seq[Path]): IO[Seq[Path]] = {
    IO.parSequence {
      sourcePath.map { path =>
        fs2.io.file.Files[IO]
          .walk(fs2.io.file.Path(path.toString))
          .filter(_.extName == ".scala")
          .compile
          .toList
      }
    }.map(_.flatten.map(_.toNioPath))
  }

  val javaCompiler = JavaTools(JavaCompiler.local.getOrElse(sys.error("No java compiler")), Javadoc.local.getOrElse(sys.error("No java doc")))
  val incrementalCompiler = IncrementalCompilerImpl()

  val outputDir = Files.createTempDirectory("zinc-classes-directory")

  override def compile(
      scopeId: String,
      classpath: List[String],
      sourcePath: List[String],
      scalacOptions: List[String],
      javacOptions: List[String],
      scalaVersion: csp.ScalaVersion,
  ): IO[csp.CompileOutput] = {

    val sources0 = findFiles(sourcePath.map(Path.of(_)))
      .map(_.map(p => VirtualSourceFile(p, HashedContent.of(p)))) // TODO: THIS WILL REHASH ALL SOURCES ON EVERY COMPILATION, LETS CACHE IT

    // FIXME: I'm bad
    val outputClassDir = outputDir.resolve("classes")
    if !Files.exists(outputClassDir) then Files.createDirectories(outputClassDir)

    val classpath0: List[PlainVirtualFile] = (classpath).map(Path.of(_)).map(PlainVirtualFile.apply(_))

    val compilers0 = incrementalCompiler.compilers(javaCompiler, ScalaInstanceProvider.getScalaInstance(scalaVersion.version))

    val outputClassJar = OutputJarWithDirTemp(outputDir.resolve("classes"), outputDir.resolve("temp"), s"${scopeId}-classes") // + fingerprint

    val analysisJar = OutputJar(outputDir.resolve("analysis"), outputDir.resolve("temp"), s"${scopeId}-analysis") // + fingerprint
    val analysisStore = new ZincCliAnalysisStore(analysisJar)

    // val signatureAnalysisJar = OutputJar(outputDir, s"${scopeId}-signature-analysis")
    // val signatureAnalysisStore = new ZincCliAnalysisStore(signatureAnalysisJar)

    val previousResult = analysisStore.get().map { analysisResult =>
      PreviousResult.create(analysisResult.getAnalysis(), analysisResult.getMiniSetup())
    }.orElse(PreviousResult.create(Optional.empty[CompileAnalysis](), Optional.empty[MiniSetup]()))

    val result = for {
      sources <- sources0
    } yield {
      System.err.println(s"sources: $sources")
      System.err.println(s"classpath: $classpath0")

      val fullClasspathMapped = classpath0.groupBy(_.toPath).map { (k, v) =>
        k -> v.head
      }

      val fileConverter = new ZincFileConverter(sources.map(s => s -> s).toMap, fullClasspathMapped)

      val incOptions = IncOptions.create()
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
        reporter = ZincCliReporter(),
        progress = None,
        earlyAnalysisStore = None,
        extra = Array()
      )

      val tempClassDir = outputDir.resolve("temp-class-dir")
      if !Files.exists(tempClassDir) then Files.createDirectories(tempClassDir)

      // val maybeSignatureAnalysisJar = if signatureAnalysisJar.path.toFile.exists() then Some(signatureAnalysisJar.path) else None

      val inputs = incrementalCompiler.inputs(
        classpath = (PlainVirtualFile(outputClassJar.temp) +: classpath0).toArray,
        sources = sources.toArray,
        classesDirectory = outputClassJar.temp,
        earlyJarPath = None,
        // scalacOptions = Nil.toArray, // List("-Ystop-after:pickler", s"-Xearly-tasty-output:${classesDirectory0.resolve("tastyFiles.jar")}").toArray,
        scalacOptions = List("-Ybest-effort", "-Ywith-best-effort-tasty", "-Ystop-before:instrumentCoverage").toArray,
        javacOptions = javacOptions.toArray,
        maxErrors = Int.MaxValue,
        sourcePositionMappers = Array(),
        order = CompileOrder.Mixed,
        compilers = compilers0,
        setup = incrementalSetup,
        pr = previousResult,
        temporaryClassesDirectory = Optional.of(tempClassDir),
        converter = fileConverter,
        stampReader = ZincCliStamper()
      )

      System.err.println(inputs)

      val compilationResult = incrementalCompiler.compile(inputs, ZincCliLogger())
      ZincCliLogger().info(() => compilationResult.toString())
      if compilationResult.hasModified then
        val analysisContents = AnalysisContents.create(compilationResult.analysis(), compilationResult.setup())
        analysisStore.set(analysisContents)
        outputClassJar.cleanAndMoveToFinalDest
    }

    result >> IO(csp.CompileOutput(outputClassJar.path.toString))
  }

}
