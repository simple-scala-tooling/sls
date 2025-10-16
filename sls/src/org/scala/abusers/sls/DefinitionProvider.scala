package org.scala.abusers.sls

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Files
import io.scalaland.chimney.dsl._
import java.net.URI
import scala.meta.pc.SymbolSource
import scala.meta.pc.SymbolSource.ExternalTastySymbolSource
import scala.meta.pc.SymbolSource.InternalTastySymbolSource
import scala.meta.pc.SymbolSource.InternalClassFileSymbolSource
import scala.meta.pc.SymbolSource.ExternalClassFileSymbolSource
import scala.meta.pc.SymbolSource.ScalaFileSymbolSource
import cats.data.OptionT

class DefinitionProvider(rootPath: os.Path, lspClient: SlsLanguageClient[IO], bspStateManager: BspStateManager) {
  val cacheDir = fs2.io.file.Path.fromNioPath((rootPath / ".sls" / "sources").toNIO)

  def definition(symbolSource: SymbolSource)(using SynchronizedState): IO[List[lsp.Location]] = {
    symbolSource match {
      case tastySource: ExternalTastySymbolSource =>
        val range = tastySource.getRange().into[lsp.Range].enableBeanGetters.transform
        findLocationsFromTasty(tastySource.getTastyJarPath(), tastySource.getInJarTastyFilePath(), range).value.map(_.toList.flatten)
      case tastySource: InternalTastySymbolSource =>
        val range = tastySource.getRange().into[lsp.Range].enableBeanGetters.transform
        findScalaSource(tastySource.getSourcePath(), range).value.map(_.toList.flatten)
      case classFileSource: ExternalClassFileSymbolSource =>
        findLocationsFromClassFile(classFileSource.getClassFileJarPath(), classFileSource.getInJarClassFilePath(), classFileSource.isJava())
      case classFileSource: InternalClassFileSymbolSource =>
        findLocationFromInternalClassFile(classFileSource.getClassFilePath(), classFileSource.getFullyQualifiedPath(), classFileSource.isJava())
      case scalaSymbolSource: ScalaFileSymbolSource =>
        List(
          lsp.Location(
            uri = scalaSymbolSource.getSourcePath(),
            range = scalaSymbolSource.getRange().into[lsp.Range].enableBeanGetters.transform
          )
        ).pure
    }
  }

  def fromURI(uri: String): OptionT[IO, fs2.io.file.Path] = {
    OptionT.pure[IO](URI.create(uri).getPath).map(fs2.io.file.Path.apply)
  }

  def findLocationsFromTasty(tastyJarPath: String, inTastyJarPath: String, range: lsp.Range)(using SynchronizedState): OptionT[IO, List[lsp.Location]] = {
    for {
      tastyJarPath <- fromURI(tastyJarPath).filterF(Files[IO].exists)
      sourceJarPath <- findSourceJar(tastyJarPath)
      thisJarCacheDir = cacheDir.resolve(sourceJarPath.fileName)
      _ <- OptionT.liftF(UnzipUtils.unzipJarFromPath(sourceJarPath, thisJarCacheDir))
    } yield {
      lsp.Location(
        uri = thisJarCacheDir.resolve(inTastyJarPath.stripSuffix(".tasty") + ".scala").toString,
        range = range
      ) :: Nil
    }
  }

  def findLocationFromInternalClassFile(classFilePath: String, fullyQualifiedPath: String, isJava: Boolean): IO[List[lsp.Location]] =
    ClassFileSourceResolver(rootPath.toNIO, lspClient)
      .resolveSourceFromClassFile(classFilePath, fullyQualifiedPath, isJava).value
      .map(maybeSource => maybeSource.map { sourcePath =>
        lsp.Location(
          uri = sourcePath.toUri.toString,
          range = lsp.Range(lsp.Position(0, 0), lsp.Position(0, 0)) // TODO: Extract range via treesitter / scalameta / JDT
        )
      }.toList)

  def findLocationsFromClassFile(classJarPath: String, inClassJarPath: String, isJava: Boolean)(using SynchronizedState): IO[List[lsp.Location]] = {
    (for {
      classJarPath <- fromURI(classJarPath).filterF(Files[IO].exists)
      sourceJarPath <- findSourceJar(classJarPath)
      thisJarCacheDir = cacheDir.resolve(sourceJarPath.fileName)
      _ <- OptionT.liftF(UnzipUtils.unzipJarFromPath(sourceJarPath, thisJarCacheDir))
    } yield {
      val suffix = if isJava then ".java" else ".scala"
      lsp.Location(
        uri = thisJarCacheDir.resolve(inClassJarPath.stripSuffix(".class") + suffix).toString,
        range = lsp.Range(lsp.Position(0, 0), lsp.Position(0, 0)) // TODO: Extract range via treesitter / scalameta / JDT
      ) :: Nil
    }).value.map(_.getOrElse(Nil))
  }

  def findSourceJar(tastyJarPath: fs2.io.file.Path)(using SynchronizedState): OptionT[IO, fs2.io.file.Path] = {
    import org.scala.abusers.sls.NioConverter.asNio
    for {
      dependencyInfo <- OptionT(bspStateManager.getDependencyInfo(tastyJarPath.toNioPath.toUri))
      sourceJar <- dependencyInfo.data.artifacts.find(_.classifier.contains("sources")).map { artifact =>
        fs2.io.file.Path(artifact.uri.asNio.getPath())
      }.toOptionT[IO].filterF(Files[IO].exists)
    } yield (sourceJar)
  }


  def findScalaSource(path: String, range: lsp.Range): OptionT[IO, List[lsp.Location]] = {
    fromURI(path)
      .filterF(Files[IO].exists)
      .map { fs2Path =>
        lsp.Location(
          uri = fs2Path.toNioPath.toUri.toString,
          range = range
        ) :: Nil
      }
  }
}
