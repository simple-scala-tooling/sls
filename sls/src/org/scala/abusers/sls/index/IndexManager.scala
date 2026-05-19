package org.scala.abusers
package sls.index

import cats.effect.IO
import org.scala.abusers.csp.CompileOutput
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.ScalaBuildTargetInformation
import org.scala.abusers.sls.ScalaBuildTargetInformation.*
import org.scala.abusers.sls.SourceUri
import org.slf4j.LoggerFactory

import java.io.FileInputStream
import java.util.zip.ZipInputStream

case class IndexManager(
    projectIndex: ProjectIndex,
    dependencyIndex: DependencyIndex,
    bytecodeIndexer: BytecodeIndexer,
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def indexDependencies(targets: Set[ScalaBuildTargetInformation]): IO[Unit] = {
    val projectDirs  = targets.flatMap(t => Set(t.classesDir, t.classJarPath))
    val allClasspath = targets.flatMap(_.classpath).toList.distinct
    val depJars      = allClasspath
      .filter { p =>
        p.toNioPath.toString.endsWith(".jar") && !projectDirs.contains(p)
      }
      .filter(_.exists)

    fs2.Stream
      .emits(depJars)
      .parEvalMapUnordered(4)(jar => indexJarSafely(jar, allClasspath))
      .compile
      .drain
  }

  def indexExistingProjectArtifacts(targets: Set[ScalaBuildTargetInformation]): IO[Unit] =
    IO.parSequenceN(4) {
      targets.toList.map { target =>
        indexProjectTarget(target)
          .handleError(e => logger.error(s"Failed to index project artifacts for ${target.displayName}", e))
      }
    }.void

  def onCompilationComplete(target: ScalaBuildTargetInformation, compileOutput: CompileOutput): IO[Unit] = {
    val changedSourceUris = compileOutput.changedFiles.keys.map(p => java.nio.file.Path.of(p).toSourceUri).toSet
    val changedProducts   = compileOutput.changedFiles.values.flatten.toSet
    val outputJar         = AbsolutePath(compileOutput.outputJar)
    val indexer           = TastyIndexer(target.displayName)

    for {
      _ <- IO(
        logger.info(
          s"Indexing after compilation for ${target.displayName}, outputJar=$outputJar, changedSources=${changedSourceUris.size}, changedProducts=${changedProducts.size}"
        )
      )
      results <- indexer
        .indexJar(outputJar, target.classpath)
        .handleErrorWith { e =>
          IO(logger.error(s"Failed to TASTy-index after compilation for ${target.displayName}", e))
            .as(Map.empty)
        }
      _ <-
        if results.nonEmpty then {
          val relevant = if changedSourceUris.nonEmpty then results.filter { case (uri, _) =>
            changedSourceUris.contains(uri)
          }
          else results
          IO(logger.info(s"TASTy index: ${relevant.size} files, ${relevant.values.map(_._1.size).sum} symbols")) *>
            projectIndex.removeFiles(changedSourceUris) *>
            projectIndex.updateFiles(relevant)
        } else
          // CSP output JAR has .betasty (not .tasty) — try BeTASTy inspector
          IO(logger.info(s"No TASTy in output JAR, trying BeTASTy inspector for ${target.displayName}")) *>
            indexer
              .indexBetastyJar(outputJar, target.classpath, changedProducts)
              .handleErrorWith { e =>
                IO(logger.error(s"BeTASTy indexing failed for ${target.displayName}", e))
                  .as(Map.empty)
              }
              .flatMap { betastyResults =>
                val relevant = if changedSourceUris.nonEmpty then betastyResults.filter { case (uri, _) =>
                  changedSourceUris.contains(uri)
                }
                else betastyResults
                IO(
                  logger.info(
                    s"BeTASTy index: ${relevant.size} files (of ${betastyResults.size}), ${relevant.values.map(_._1.size).sum} symbols, ${relevant.values.map(_._2.size).sum} references. changedSourceUris=$changedSourceUris, betastyResultKeys=${betastyResults.keys}"
                  )
                ) *>
                  projectIndex.removeFiles(changedSourceUris) *>
                  projectIndex.updateFiles(relevant)
              }
    } yield ()
  }

  def onFilesDeleted(uris: Set[SourceUri]): IO[Unit] =
    projectIndex.removeFiles(uris)

  private def indexProjectTarget(target: ScalaBuildTargetInformation): IO[Unit] = {
    val classesDir  = target.classesDir
    val classJar    = target.classJarPath
    val bspClassDir = SourceUri(target.scalacOptions.classDirectory).toPath
    val indexer     = TastyIndexer(target.displayName)
    if classesDir.exists && hasTastyFiles(classesDir) then indexer
      .indexDirectory(classesDir, target.classpath)
      .flatMap(projectIndex.updateFiles)
    else if classJar.exists then indexer.indexJar(classJar, target.classpath).flatMap(projectIndex.updateFiles)
    else if bspClassDir.exists && hasTastyFiles(bspClassDir) then {
      logger.info(s"Indexing BSP class directory for ${target.displayName}: $bspClassDir")
      indexer.indexDirectory(bspClassDir, target.classpath).flatMap(projectIndex.updateFiles)
    } else {
      logger.warn(s"No indexable artifacts found for ${target.displayName}")
      IO.unit
    }
  }

  private[index] def indexJarSafely(jarPath: AbsolutePath, classpath: List[AbsolutePath]): IO[Unit] = {
    val jar                        = jarPath.toNioPath.toString
    def indexViaBytecode: IO[Unit] =
      bytecodeIndexer.indexJar(jarPath).flatMap(dependencyIndex.addJar(jar, _))

    def indexViaJavaSources(sourcesJar: AbsolutePath): IO[Boolean] =
      JavaIndexer
        .forDependency(jar)
        .indexJarEntries(sourcesJar, classpath)
        .flatMap { results =>
          val symbols = results.values.flatMap(_._1).toList
          if symbols.isEmpty then IO.pure(false)
          else dependencyIndex.addJar(jar, symbols).as(true)
        }

    (for {
      hasTasty <- jarContainsTasty(jarPath)
      _        <-
        if hasTasty then {
          val indexer = TastyIndexer("dependency")
          indexer
            .indexJar(jarPath, classpath)
            .flatMap { results =>
              val symbols = results.values.flatMap(_._1).toList
              dependencyIndex.addJar(jar, symbols)
            }
            .handleErrorWith { e =>
              logger.error(s"TASTy indexing failed for $jar, falling back to bytecode", e)
              indexViaBytecode
            }
        } else
          findSourcesJar(jarPath) match {
            case Some(sj) =>
              indexViaJavaSources(sj)
                .handleErrorWith { e =>
                  logger.error(s"Java source indexing failed for $jar (sources: $sj), falling back to bytecode", e)
                  IO.pure(false)
                }
                .flatMap { indexed =>
                  if indexed then IO.unit
                  else {
                    logger.info(s"Java source indexing produced nothing for $jar, falling back to bytecode")
                    indexViaBytecode
                  }
                }
            case None => indexViaBytecode
          }
    } yield ()).handleError(e => logger.error(s"Failed to index JAR $jar", e))
  }

  private def findSourcesJar(jarPath: AbsolutePath): Option[AbsolutePath] = {
    val nio        = jarPath.toNioPath
    val fileName   = nio.getFileName.toString
    val sourcesArt = fileName.stripSuffix(".jar") + "-sources.jar"
    val sibling    = nio.resolveSibling(sourcesArt)
    if java.nio.file.Files.exists(sibling) then Some(AbsolutePath(sibling)) else None
  }

  private def jarContainsTasty(jarPath: AbsolutePath): IO[Boolean] =
    IO.blocking {
      val zis = new ZipInputStream(new FileInputStream(jarPath.toFile))
      try
        Iterator
          .continually(zis.getNextEntry)
          .takeWhile(_ != null)
          .exists(_.getName.endsWith(".tasty"))
      finally zis.close()
    }

  private def hasTastyFiles(dir: AbsolutePath): Boolean =
    java.nio.file.Files.walk(dir.toNioPath).anyMatch(p => p.toString.endsWith(".tasty"))
}

object IndexManager {
  def apply(
      projectIndex: ProjectIndex,
      dependencyIndex: DependencyIndex,
      bytecodeIndexer: BytecodeIndexer,
  ): IndexManager = new IndexManager(projectIndex, dependencyIndex, bytecodeIndexer)
}
