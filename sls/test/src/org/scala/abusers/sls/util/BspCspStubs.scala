package org.scala.abusers.sls.util

import cats.effect.IO
import dotty.tools.dotc.util.ClasspathFromClassloader
import org.scala.abusers.csp
import org.scala.abusers.sls.BuildServer

import java.io.File.pathSeparator
import java.nio.file.Files
import java.nio.file.Path

object BspStubs {

  private def nope[A](op: String): IO[A] =
    IO.raiseError(new NotImplementedError(s"BSP stub does not implement $op"))

  /** A [[BuildServer]] whose only real behaviour is answering inverse-sources with the given target id — the one BSP
    * call `BspStateManager.didOpen` makes. Everything else raises, so a test that strays off the supported path fails
    * loudly instead of hanging.
    */
  def forTarget(targetId: bsp.BuildTargetIdentifier): BuildServer = BuildServer(
    generic = new bsp.BuildServer[IO] {
      def buildTargetInverseSources(input: bsp.InverseSourcesParams): IO[bsp.InverseSourcesResult] =
        IO.pure(bsp.InverseSourcesResult(List(targetId)))
      def onBuildInitialized(): IO[Unit] = IO.unit

      def buildInitialize(input: bsp.InitializeBuildParams): IO[bsp.InitializeBuildResult] = nope("buildInitialize")
      def buildShutdown(): IO[Unit]                                                        = nope("buildShutdown")
      def onBuildExit(): IO[Unit]                                                          = nope("onBuildExit")
      def workspaceBuildTargets(): IO[bsp.WorkspaceBuildTargetsResult]                     = nope("workspaceBuildTargets")
      def workspaceReload(): IO[Unit]                                                      = nope("workspaceReload")
      def buildTargetSources(input: bsp.SourcesParams): IO[bsp.SourcesResult]              = nope("buildTargetSources")
      def buildTargetDependencySources(input: bsp.DependencySourcesParams): IO[bsp.DependencySourcesResult] =
        nope("buildTargetDependencySources")
      def buildTargetDependencyModules(input: bsp.DependencyModulesParams): IO[bsp.DependencyModulesResult] =
        nope("buildTargetDependencyModules")
      def buildTargetResources(input: bsp.ResourcesParams): IO[bsp.ResourcesResult]    = nope("buildTargetResources")
      def buildTargetOutputPaths(input: bsp.OutputPathsParams): IO[bsp.OutputPathsResult] =
        nope("buildTargetOutputPaths")
      def buildTargetCompile(input: bsp.CompileParams): IO[bsp.CompileResult]          = nope("buildTargetCompile")
      def buildTargetRun(input: bsp.BuildTargetRunInput): IO[bsp.RunResult]            = nope("buildTargetRun")
      def buildTargetTest(input: bsp.BuildTargetTestInput): IO[bsp.TestResult]         = nope("buildTargetTest")
      def buildTargetCleanCache(input: bsp.CleanCacheParams): IO[bsp.CleanCacheResult] = nope("buildTargetCleanCache")
      def debugSessionStart(input: bsp.DebugSessionStartInput): IO[bsp.DebugSessionAddress] =
        nope("debugSessionStart")
      def onRunReadStdin(input: bsp.ReadParams): IO[Unit] = nope("onRunReadStdin")
    },
    jvm = new bsp.jvm.JvmBuildServer[IO] {
      def buildTargetJvmCompileClasspath(input: bsp.jvm.JvmCompileClasspathParams): IO[bsp.jvm.JvmCompileClasspathResult] =
        nope("buildTargetJvmCompileClasspath")
      def buildTargetJvmRunEnvironment(input: bsp.jvm.JvmRunEnvironmentParams): IO[bsp.jvm.JvmRunEnvironmentResult] =
        nope("buildTargetJvmRunEnvironment")
      def buildTargetJvmTestEnvironment(input: bsp.jvm.JvmTestEnvironmentParams): IO[bsp.jvm.JvmTestEnvironmentResult] =
        nope("buildTargetJvmTestEnvironment")
    },
    scala = new bsp.scala_.ScalaBuildServer[IO] {
      def buildTargetScalacOptions(input: bsp.scala_.ScalacOptionsParams): IO[bsp.scala_.ScalacOptionsResult] =
        nope("buildTargetScalacOptions")
      def buildTargetScalaMainClasses(input: bsp.scala_.ScalaMainClassesParams): IO[bsp.scala_.ScalaMainClassesResult] =
        nope("buildTargetScalaMainClasses")
      def buildTargetScalaTestClasses(input: bsp.scala_.ScalaTestClassesParams): IO[bsp.scala_.ScalaTestClassesResult] =
        nope("buildTargetScalaTestClasses")
    },
    java = new bsp.java_.JavaBuildServer[IO] {
      def buildTargetJavacOptions(input: bsp.java_.JavacOptionsParams): IO[bsp.java_.JavacOptionsResult] =
        nope("buildTargetJavacOptions")
    },
  )
}

object FakeCspServer {

  /** A CSP double backed by an in-process dotc run: compiles `sourcePath` straight into a fresh jar under `workDir`
    * and reports it as [[csp.OutputFormat.TASTY]] — real compiler output without the zinc machinery. `changedFiles`
    * is left empty ("unknown changes"), so `IndexManager.onCompilationComplete` indexes the whole jar.
    *
    * Nonexistent classpath entries (e.g. the synthetic target's `./.sls/` paths) are filtered out; the test runtime
    * classpath is appended so the standard library resolves.
    */
  def dotcBacked(workDir: os.Path): csp.CspServer[IO] = new csp.CspServer[IO] {
    def compile(
        scopeId: String,
        classpath: List[String],
        sourcePath: List[String],
        scalacOptions: List[String],
        javacOptions: List[String],
        scalaVersion: csp.ScalaVersion,
    ): IO[csp.CompileOutput] = IO.blocking {
      val outJar = workDir / s"$scopeId-${System.nanoTime()}.jar"
      val cp     = (classpath.filter(p => Files.exists(Path.of(p))) :+ ClasspathFromClassloader(getClass.getClassLoader))
        .mkString(pathSeparator)
      val args     = Array("-d", outJar.toString, "-classpath", cp) ++ sourcePath
      val reporter = dotty.tools.dotc.Main.process(args)
      if reporter.hasErrors then
        throw new RuntimeException(s"FakeCspServer compilation failed with ${reporter.errorCount} error(s)")
      csp.CompileOutput(outJar.toString, changedFiles = Map.empty, outputFormat = csp.OutputFormat.TASTY)
    }
  }
}
