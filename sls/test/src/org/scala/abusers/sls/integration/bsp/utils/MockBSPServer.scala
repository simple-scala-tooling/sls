package org.scala.abusers.sls.integration.bsp.utils

import bsp.scala_.ScalaPlatform.JVM
import bsp.BuildTarget
import cats.effect.kernel.Ref
import cats.effect.IO
import cats.syntax.all.*
import org.scala.abusers.sls.BuildServer

/** Simple stub implementations of BSP services for testing. Uses smithy4s pattern of directly implementing the
  * generated service interfaces.
  */
class MockBSPServer(
    compileRequestsRef: Ref[IO, List[bsp.CompileParams]],
    connectedRef: Ref[IO, Boolean],
) {

  /** Creates a BuildServer with stub implementations for testing */
  def createBuildServer: BuildServer = BuildServer(
    generic = new StubBuildServer(connectedRef, compileRequestsRef),
    jvm = new StubJvmBuildServer,
    scala = new StubScalaBuildServer,
    java = new StubJavaBuildServer,
  )

  def getCompileRequests: IO[List[bsp.CompileParams]] = compileRequestsRef.get
  def isConnected: IO[Boolean]                        = connectedRef.get
  def clearRequests: IO[Unit]                         = compileRequestsRef.set(List.empty)
}

/** Stub implementation of BuildServer for testing */
class StubBuildServer(
    connectedRef: Ref[IO, Boolean],
    compileRequestsRef: Ref[IO, List[bsp.CompileParams]],
) extends bsp.BuildServer[IO] {

  def buildInitialize(params: bsp.InitializeBuildParams): IO[bsp.InitializeBuildResult] =
    connectedRef.set(true) *>
      IO.pure(
        bsp.InitializeBuildResult(
          displayName = "Mock BSP Server",
          version = "1.0.0",
          bspVersion = "2.1.0",
          capabilities = bsp.BuildServerCapabilities(
            compileProvider = Some(bsp.CompileProvider(languageIds = List(bsp.LanguageId("scala")))),
            testProvider = Some(bsp.TestProvider(languageIds = List(bsp.LanguageId("scala")))),
            runProvider = Some(bsp.RunProvider(languageIds = List(bsp.LanguageId("scala")))),
            debugProvider = Some(bsp.DebugProvider(languageIds = List(bsp.LanguageId("scala")))),
            inverseSourcesProvider = true,
            dependencySourcesProvider = true,
            dependencyModulesProvider = true,
            resourcesProvider = true,
            outputPathsProvider = true,
            buildTargetChangedProvider = true,
            jvmRunEnvironmentProvider = true,
            jvmTestEnvironmentProvider = true,
            canReload = true,
          ),
        )
      )

  def onBuildInitialized(): IO[Unit] = IO.unit
  def buildShutdown(): IO[Unit]      = connectedRef.set(false)
  def onBuildExit(): IO[Unit]        = IO.unit
  def workspaceReload(): IO[Unit]    = IO.unit

  def workspaceBuildTargets(): IO[bsp.WorkspaceBuildTargetsResult] =
    // Set connected state when BSP server is actually used
    connectedRef.set(true) *> {
      // Create a basic mock build target for testing
      val testTargetId = bsp.BuildTargetIdentifier(bsp.URI("file:///test-target"))
      val mockTarget = bsp.BuildTarget.BuildTargetScalaBuildTarget(
        id = testTargetId,
        displayName = Some("mock-target"),
        baseDirectory = Some(bsp.URI("file:///mock")),
        tags = List(bsp.BuildTargetTag.LIBRARY),
        capabilities = bsp.BuildTargetCapabilities(
          canCompile = Some(true),
          canTest = Some(true),
          canRun = Some(true),
          canDebug = Some(false),
        ),
        languageIds = List(bsp.LanguageId("scala")),
        dependencies = List.empty,
        data = bsp.scala_.ScalaBuildTarget(
          scalaOrganization = "org.scala-lang",
          scalaVersion = "3.7.2-RC1-bin-20250616-61d9887-NIGHTLY",
          scalaBinaryVersion = "3",
          platform = JVM,
          jars = List(bsp.URI("file:///mock/lib/scala-library.jar")),
        ),
      )
      IO.pure(bsp.WorkspaceBuildTargetsResult(List(mockTarget)))
    }

  def buildTargetSources(params: bsp.SourcesParams): IO[bsp.SourcesResult] = {
    val sourceItems = params.targets.map { targetId =>
      bsp.SourcesItem(
        target = targetId,
        sources = List(
          bsp.SourceItem(
            uri = bsp.URI("file:///mock/src/main/scala/Test.scala"),
            kind = bsp.SourceItemKind.FILE,
            generated = false,
          )
        ),
        roots = Some(List(bsp.URI("file:///mock/src/main/scala"))),
      )
    }
    IO.pure(bsp.SourcesResult(sourceItems))
  }

  def buildTargetInverseSources(params: bsp.InverseSourcesParams): IO[bsp.InverseSourcesResult] = {
    val testTargetId = bsp.BuildTargetIdentifier(bsp.URI("file:///test-target"))
    IO.pure(bsp.InverseSourcesResult(List(testTargetId)))
  }

  def buildTargetDependencySources(params: bsp.DependencySourcesParams): IO[bsp.DependencySourcesResult] =
    IO.pure(bsp.DependencySourcesResult(List.empty))

  def buildTargetDependencyModules(params: bsp.DependencyModulesParams): IO[bsp.DependencyModulesResult] =
    IO.pure(bsp.DependencyModulesResult(List.empty))

  def buildTargetResources(params: bsp.ResourcesParams): IO[bsp.ResourcesResult] =
    IO.pure(bsp.ResourcesResult(List.empty))

  def buildTargetOutputPaths(params: bsp.OutputPathsParams): IO[bsp.OutputPathsResult] =
    IO.pure(bsp.OutputPathsResult(List.empty))

  def buildTargetCompile(params: bsp.CompileParams): IO[bsp.CompileResult] =
    compileRequestsRef.update(_ :+ params) *>
      IO.pure(
        bsp.CompileResult(
          statusCode = bsp.StatusCode.OK,
          originId = params.originId,
          data = None,
        )
      )

  def buildTargetTest(params: bsp.TestParams): IO[bsp.TestResult] =
    IO.pure(
      bsp.TestResult(
        statusCode = bsp.StatusCode.OK,
        originId = params.originId,
        data = None,
      )
    )

  def buildTargetRun(params: bsp.RunParams): IO[bsp.RunResult] =
    IO.pure(
      bsp.RunResult(
        originId = params.originId,
        statusCode = bsp.StatusCode.OK,
      )
    )

  def debugSessionStart(params: bsp.DebugSessionParams): IO[bsp.DebugSessionAddress] =
    IO.raiseError(new UnsupportedOperationException("Debug not supported in mock"))

  def buildTargetCleanCache(params: bsp.CleanCacheParams): IO[bsp.CleanCacheResult] =
    IO.pure(bsp.CleanCacheResult(message = Some("Cleaned"), cleaned = true))

  def onRunReadStdin(input: bsp.ReadParams): IO[Unit] = IO.unit

  // Input-based methods for new BSP interface
  def buildTargetRun(input: bsp.BuildTargetRunInput): IO[bsp.RunResult] =
    buildTargetRun(input.data)

  def buildTargetTest(input: bsp.BuildTargetTestInput): IO[bsp.TestResult] =
    buildTargetTest(input.data)

  def debugSessionStart(input: bsp.DebugSessionStartInput): IO[bsp.DebugSessionAddress] =
    debugSessionStart(input.data)
}

/** Stub implementation of JvmBuildServer for testing */
class StubJvmBuildServer extends bsp.jvm.JvmBuildServer[IO] {
  def buildTargetJvmRunEnvironment(params: bsp.jvm.JvmRunEnvironmentParams): IO[bsp.jvm.JvmRunEnvironmentResult] =
    IO.pure(bsp.jvm.JvmRunEnvironmentResult(List.empty))

  def buildTargetJvmTestEnvironment(params: bsp.jvm.JvmTestEnvironmentParams): IO[bsp.jvm.JvmTestEnvironmentResult] =
    IO.pure(bsp.jvm.JvmTestEnvironmentResult(List.empty))

  def buildTargetJvmCompileClasspath(params: bsp.jvm.JvmCompileClasspathParams): IO[bsp.jvm.JvmCompileClasspathResult] =
    IO.pure(bsp.jvm.JvmCompileClasspathResult(List.empty))
}

/** Stub implementation of ScalaBuildServer for testing */
class StubScalaBuildServer extends bsp.scala_.ScalaBuildServer[IO] {
  def buildTargetScalacOptions(params: bsp.scala_.ScalacOptionsParams): IO[bsp.scala_.ScalacOptionsResult] = {
    val scalaOptions = params.targets.map { targetId =>
      bsp.scala_.ScalacOptionsItem(
        target = targetId,
        options = List("-unchecked", "-deprecation"),
        classpath = List("file:///mock/lib/scala-library.jar"),
        classDirectory = "file:///mock/target/classes",
      )
    }
    IO.pure(bsp.scala_.ScalacOptionsResult(scalaOptions))
  }

  def buildTargetScalaTestClasses(params: bsp.scala_.ScalaTestClassesParams): IO[bsp.scala_.ScalaTestClassesResult] =
    IO.pure(bsp.scala_.ScalaTestClassesResult(List.empty))

  def buildTargetScalaMainClasses(params: bsp.scala_.ScalaMainClassesParams): IO[bsp.scala_.ScalaMainClassesResult] =
    IO.pure(bsp.scala_.ScalaMainClassesResult(List.empty))
}

/** Stub implementation of JavaBuildServer for testing */
class StubJavaBuildServer extends bsp.java_.JavaBuildServer[IO] {
  def buildTargetJavacOptions(params: bsp.java_.JavacOptionsParams): IO[bsp.java_.JavacOptionsResult] =
    IO.pure(bsp.java_.JavacOptionsResult(List.empty))
}

object MockBSPServer {

  /** Creates a basic MockBSPServer for testing */
  def create: IO[MockBSPServer] =
    for {
      compileRequestsRef <- Ref.of[IO, List[bsp.CompileParams]](List.empty)
      connectedRef       <- Ref.of[IO, Boolean](false)
    } yield MockBSPServer(compileRequestsRef, connectedRef)

  /** Creates MockBSPServer with default test configuration */
  def withDefaultTargets: IO[MockBSPServer] = create
}
