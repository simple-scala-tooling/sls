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

type ScalaBuildTargetInformation = (scalacOptions: ScalacOptionsItem, buildTarget: BuildTargetScalaBuildTarget)

object ScalaBuildTargetInformation {
  extension (buildTargetInformation: ScalaBuildTargetInformation) {
    def scalaVersion: ScalaVersion =
      ScalaVersion(buildTargetInformation.buildTarget.data.scalaVersion)

    def classpath: List[os.Path] =
      buildTargetInformation.scalacOptions.classpath.map(entry => os.Path(URI.create(entry)))

    def compilerOptions: List[String] = buildTargetInformation.scalacOptions.options
  }
}

object BspStateManager {

  def instance(bspServer: BuildServer): IO[BspStateManager] =
    // We should track this in progress bar. Think of this as `Import Build`
    for {
      sourcesToTargets <- AtomicCell[IO].of(Map[URI, ScalaBuildTargetInformation]())
      buildTargets     <- Ref.of[IO, Set[ScalaBuildTargetInformation]](Set.empty)
    } yield BspStateManager(bspServer, sourcesToTargets, buildTargets)
}

/** Class responsible for tracking and handling map between file and target we want to compile it against
  *
  * One of the problems we will face, is that a single file can belong to multiple build targets e.g cross-compilation
  * Ideally we should prompt the user in such scenarios, to choose which target he wants to use to provide interactive
  * feature Another option will be to default to latest version which after all I'll default to right now
  */
class BspStateManager(
    val bspServer: BuildServer,
    sourcesToTargets: AtomicCell[IO, Map[URI, ScalaBuildTargetInformation]],
    targets: Ref[IO, Set[ScalaBuildTargetInformation]],
) {
  import ScalaBuildTargetInformation.*

  def importBuild(client: SlsLanguageClient[IO]) =
    for {
      _             <- client.logMessage("Starting build import.") // in the future this should be a task with progress
      importedBuild <- getBuildInformation(bspServer)
      _ <- bspServer.generic.buildTargetCompile(CompileParams(targets = importedBuild.map(_.buildTarget.id).toList))
      _ <- targets.set(importedBuild)
      _ <- client.logMessage("Build import finished.")
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
      ) //
    } yield buildTargetToScalaTargets(workspaceBuildTargets, scalacOptions)
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

  private def buildTargetToScalaTargets(
      targets: bsp.WorkspaceBuildTargetsResult,
      scalacOptions: bsp.scala_.ScalacOptionsResult,
  ): Set[ScalaBuildTargetInformation] = {
    val scalacOptions0 = scalacOptions.items.map(item => item.target -> item).toMap
    val (mismatchedTargets, zippedTargets) = targets.targets.partitionMap { target =>
      scalacOptions0.get(target.id) match {
        case Some(scalacOptionsItem) if target.project.scala.isDefined =>
          Right(scalacOptions = scalacOptionsItem, buildTarget = target.project.scala.get)
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
  def get(uri: URI): IO[ScalaBuildTargetInformation] =
    sourcesToTargets.get.map { state =>
      state.getOrElse(uri, throw new IllegalStateException("Get should always be called after didOpen"))
    }

  def didOpen(client: SlsLanguageClient[IO], params: lsp.DidOpenTextDocumentParams): IO[Unit] = {
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
