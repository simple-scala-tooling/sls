package org.scala.abusers.sls

import bsp.scala_.ScalacOptionsItem
import bsp.scala_.ScalacOptionsParams
import bsp.BuildTarget.BuildTargetScalaBuildTarget
import bsp.CompileParams
import bsp.InverseSourcesParams
import cats.effect.kernel.Ref
import cats.effect.std.AtomicCell
import cats.effect.IO
import org.scala.abusers.pc.ScalaVersion
import org.scala.abusers.sls.LoggingUtils.*

import java.net.URI
import bsp.SourcesParams
import java.nio.file.Paths
import org.scala.abusers.csp.CspServer
import org.scala.abusers.csp.CompileOutput

case class ScalaBuildTargetInformation(
    scalacOptions: ScalacOptionsItem,
    buildTarget: BuildTargetScalaBuildTarget,
    sources: bsp.SourcesItem,
) {
  // Refactor this to some other way
  val displayName = buildTarget.displayName.getOrElse("unknown-target") // log that there is no name ?
  val classJarPath: java.nio.file.Path = Paths.get("./.sls/classes/")
    .resolve(s"$displayName.jar").toAbsolutePath()

  val classesDir: java.nio.file.Path = Paths.get("./.sls/classes/")
    .resolve(s"$displayName/").toAbsolutePath()

  val osLibClassJarPath = os.Path(classJarPath)
  val osLibClassesDir = os.Path(classesDir)
}

object ScalaBuildTargetInformation {
  extension (buildTargetInformation: ScalaBuildTargetInformation) {
    def scalaVersion: ScalaVersion =
      ScalaVersion(buildTargetInformation.buildTarget.data.scalaVersion)

    def classpath: List[os.Path] = List(buildTargetInformation.osLibClassesDir, buildTargetInformation.osLibClassJarPath) ++
      buildTargetInformation.scalacOptions.classpath.map(entry => os.Path(URI.create(entry)))

    def compilerOptions: List[String] = buildTargetInformation.scalacOptions.options
  }
}

object BspStateManager {

  def instance(lspClient: SlsLanguageClient[IO], bspServer: BuildServer, cspServer: CspServer[IO]): IO[BspStateManager] =
    // We should track this in progress bar. Think of this as `Import Build`
    for {
      sourcesToTargets <- AtomicCell[IO].of(Map[URI, ScalaBuildTargetInformation]())
      buildTargets     <- Ref.of[IO, Set[ScalaBuildTargetInformation]](Set.empty)
    } yield BspStateManager(lspClient, bspServer, cspServer, sourcesToTargets, buildTargets)
}

/** Class responsible for tracking and handling map between file and target we want to compile it against
  *
  * One of the problems we will face, is that a single file can belong to multiple build targets e.g cross-compilation
  * Ideally we should prompt the user in such scenarios, to choose which target he wants to use to provide interactive
  * feature Another option will be to default to latest version which after all I'll default to right now
  */
class BspStateManager(
    lspClient: SlsLanguageClient[IO],
    bspServer: BuildServer,
    cspServer: CspServer[IO],
    sourcesToTargets: AtomicCell[IO, Map[URI, ScalaBuildTargetInformation]],
    targets: Ref[IO, Set[ScalaBuildTargetInformation]],
) {
  import ScalaBuildTargetInformation.*

  def compileWithCSP(uri: URI)(using SynchronizedState): IO[CompileOutput] = {
    get(uri).flatMap { info =>
      cspServer.compile(
        scopeId = info.buildTarget.displayName.getOrElse("default"),
        classpath = info.classpath.map(_.toString),
        sourcePath = info.sources._2.map(p => URI(p.uri.value).getPath()),
        scalaVersion = org.scala.abusers.csp.ScalaVersion(info.buildTarget.data.scalaVersion),
        scalacOptions = info.scalacOptions.options,
        javacOptions = Nil
      )
    }
  }

  def importBuild =
    for {
      _ <- lspClient.logMessage("Starting build import.") // in the future this should be a task with progress
      importedBuild <- getBuildInformation(bspServer)
      _ <- bspServer.generic.buildTargetCompile(CompileParams(targets = importedBuild.map(_.buildTarget.id).toList))
      _ <- targets.set(importedBuild)
      _ <- lspClient.logMessage("Build import finished.")
    } yield ()

  private val byScalaVersion: Ordering[ScalaBuildTargetInformation] = new Ordering[ScalaBuildTargetInformation] {
    override def compare(x: ScalaBuildTargetInformation, y: ScalaBuildTargetInformation): Int =
      Ordering[ScalaVersion].compare(x.scalaVersion, y.scalaVersion)
  }

  private def getBuildInformation(bspServer: BuildServer): IO[Set[ScalaBuildTargetInformation]] =
    for {
      workspaceBuildTargets <- bspServer.generic.workspaceBuildTargets()
      scalacOptions <- bspServer.scala.buildTargetScalacOptions(
        ScalacOptionsParams(targets = workspaceBuildTargets.targets.map(_.id))
      )
      targetSources <- bspServer.generic.buildTargetSources(SourcesParams(workspaceBuildTargets.targets.map(_.id)))
    } yield buildTargetToScalaTargets(workspaceBuildTargets, scalacOptions, targetSources)
      .groupMapReduce(_.buildTarget.id)(identity)(byScalaVersion.max)
      .values
      .toSet

  private def buildTargetInverseSources(uri: URI): IO[List[bsp.BuildTargetIdentifier]] =
    for inverseSources <- bspServer.generic
      .buildTargetInverseSources(
        InverseSourcesParams(
          textDocument = bsp.TextDocumentIdentifier(bsp.URI(uri.toString))
        )
      )
    yield inverseSources.targets

  private def isSyntheticTarget(target: bsp.BuildTarget): Boolean =
    target.id.uri.value.contains("mill-synthetic-root-target")

  private def buildTargetToScalaTargets(
      targets0: bsp.WorkspaceBuildTargetsResult,
      scalacOptions: bsp.scala_.ScalacOptionsResult,
      sources: bsp.SourcesResult,
  ): Set[ScalaBuildTargetInformation] = {
    val targets = targets0.targets.filterNot(isSyntheticTarget)
    val scalacOptions0 = scalacOptions.items.map(item => item.target -> item).toMap
    val sources0 = sources.items.map(item => item.target -> item).toMap
    val (mismatchedTargets, zippedTargets) = targets.partitionMap { target =>
      (scalacOptions0.get(target.id), sources0.get(target.id)) match {
        case (Some(scalacOptionsItem), Some(sources)) if target.project.scala.isDefined =>
          Right(ScalaBuildTargetInformation(scalacOptions = scalacOptionsItem, buildTarget = target.project.scala.get, sources = sources))
        case _ => Left(target.id)
      }
    }

    if mismatchedTargets.nonEmpty then throw new IllegalStateException(
      s"Mismatched targets to ScalacOptionsResult probably caused due to existance of java scopes. ${mismatchedTargets.mkString(", ")}"
    )
    else zippedTargets.toSet
  }

  /** didOpen / didChange is always a first request in sequence e.g didChange -> completion -> semanticTokens
    *
    * We want to fail fast if this is not the case because it is a way bigger problem that we may hide
    */
  def get(uri: URI)(using SynchronizedState): IO[ScalaBuildTargetInformation] =
    sourcesToTargets.get.map { state =>
      state.getOrElse(uri, throw new IllegalStateException("Get should always be called after didOpen"))
    }

  def didOpen(
      client: SlsLanguageClient[IO],
      params: lsp.DidOpenTextDocumentParams,
  )(using SynchronizedState): IO[Unit] = {
    val uri = URI.create(params.textDocument.uri)
    sourcesToTargets.evalUpdate(state =>
      for {
        possibleIds <- buildTargetInverseSources(uri)
        targets0    <- targets.get
        possibleBuildTargets = possibleIds.flatMap(id => targets0.find(_.buildTarget.id == id))
        bestBuildTarget      = possibleBuildTargets.maxBy(_.buildTarget.project.scala.map(_.data.scalaVersion))
        _ <- client.logDebug(s"Best build target for $uri is ${bestBuildTarget.toString}")
      } yield state.updated(uri, bestBuildTarget)
    )
  }
}

// to be used in the future
// def didChangeConfiguration =
//   for
//     bspServer0       <- bspServer.get
//     importedBuild <- getBuildInformation(bspServer0)
//     _             <- bspServer0.generic.buildTargetCompile(importedBuild.map(_.buildTarget.id).toList)
//     _                <- targets.set(importedBuild)
//   yield ()

// didRename
// didRemove
