package org.scala.abusers
package sls.index

import cats.effect.IO
import org.scala.abusers.sls.ScalaBuildTargetInformation
import org.scala.abusers.sls.ScalaBuildTargetInformation.*
import org.scala.abusers.csp.CompileOutput
import java.net.URI
import java.util.zip.ZipInputStream
import java.io.FileInputStream
import org.slf4j.LoggerFactory

case class IndexManager(
    projectIndex: ProjectIndex,
    dependencyIndex: DependencyIndex,
    bytecodeIndexer: BytecodeIndexer
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def indexDependencies(targets: Set[ScalaBuildTargetInformation]): IO[Unit] = {
    val projectDirs = targets.flatMap(t => Set(t.osLibClassesDir, t.osLibClassJarPath))
    val allClasspath = targets.flatMap(_.classpath).toList.distinct
    val depJars = allClasspath.filter { p =>
      p.toString.endsWith(".jar") && !projectDirs.contains(p)
    }.filter(os.exists(_))

    fs2.Stream.emits(depJars)
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
    val changedSourceUris = compileOutput.changedFiles.keys.map(p => java.nio.file.Path.of(p).toUri).toSet
    val changedProducts = compileOutput.changedFiles.values.flatten.toSet
    val outputJar = os.Path(compileOutput.outputJar)
    val indexer = TastyIndexer(target.displayName)

    for {
      _ <- IO(logger.info(s"Indexing after compilation for ${target.displayName}, outputJar=$outputJar, changedSources=${changedSourceUris.size}, changedProducts=${changedProducts.size}"))
      results <- indexer.indexJar(outputJar, target.classpath)
        .handleErrorWith { e =>
          IO(logger.error(s"Failed to TASTy-index after compilation for ${target.displayName}", e))
            .as(Map.empty)
        }
      _ <- if results.nonEmpty then
             val relevant = if changedSourceUris.nonEmpty then results.filter { case (uri, _) => changedSourceUris.contains(uri) } else results
             IO(logger.info(s"TASTy index: ${relevant.size} files, ${relevant.values.map(_._1.size).sum} symbols")) *>
             projectIndex.removeFiles(changedSourceUris) *>
             projectIndex.updateFiles(relevant)
           else
             // CSP output JAR has .betasty (not .tasty) — try BeTASTy inspector
             IO(logger.info(s"No TASTy in output JAR, trying BeTASTy inspector for ${target.displayName}")) *>
             indexer.indexBetastyJar(outputJar, target.classpath, changedProducts)
               .handleErrorWith { e =>
                 IO(logger.error(s"BeTASTy indexing failed for ${target.displayName}", e))
                   .as(Map.empty)
               }
               .flatMap { betastyResults =>
                 val relevant = if changedSourceUris.nonEmpty then betastyResults.filter { case (uri, _) => changedSourceUris.contains(uri) } else betastyResults
                 IO(logger.info(s"BeTASTy index: ${relevant.size} files (of ${betastyResults.size}), ${relevant.values.map(_._1.size).sum} symbols, ${relevant.values.map(_._2.size).sum} references. changedSourceUris=$changedSourceUris, betastyResultKeys=${betastyResults.keys}")) *>
                 projectIndex.removeFiles(changedSourceUris) *>
                 projectIndex.updateFiles(relevant)
               }
    } yield ()
  }

  def onFilesDeleted(uris: Set[URI]): IO[Unit] =
    projectIndex.removeFiles(uris)

  private def indexProjectTarget(target: ScalaBuildTargetInformation): IO[Unit] = {
    val classesDir = target.osLibClassesDir
    val classJar = target.osLibClassJarPath
    val bspClassDir = os.Path(java.net.URI.create(target.scalacOptions.classDirectory).getPath)
    val indexer = TastyIndexer(target.displayName)
    if os.exists(classesDir) && hasTastyFiles(classesDir) then
      indexer.indexDirectory(classesDir, target.classpath).flatMap(projectIndex.updateFiles)
    else if os.exists(classJar) then
      indexer.indexJar(classJar, target.classpath).flatMap(projectIndex.updateFiles)
    else if os.exists(bspClassDir) && hasTastyFiles(bspClassDir) then
      logger.info(s"Indexing BSP class directory for ${target.displayName}: $bspClassDir")
      indexer.indexDirectory(bspClassDir, target.classpath).flatMap(projectIndex.updateFiles)
    else
      logger.warn(s"No indexable artifacts found for ${target.displayName}")
      IO.unit
  }

  private def indexJarSafely(jarPath: os.Path, classpath: List[os.Path]): IO[Unit] = {
    val jar = jarPath.toString
    def indexViaBytecode: IO[Unit] =
      bytecodeIndexer.indexJar(jarPath).flatMap(dependencyIndex.addJar(jar, _))

    (for {
      hasTasty <- jarContainsTasty(jarPath)
      _ <-
        if hasTasty then
          val indexer = TastyIndexer("dependency")
          indexer.indexJar(jarPath, classpath).flatMap { results =>
            val symbols = results.values.flatMap(_._1).toList
            dependencyIndex.addJar(jar, symbols)
          }.handleErrorWith { e =>
            logger.error(s"TASTy indexing failed for $jar, falling back to bytecode", e)
            indexViaBytecode
          }
        else
          indexViaBytecode
    } yield ()).handleError(e => logger.error(s"Failed to index JAR $jar", e))
  }

  private def jarContainsTasty(jarPath: os.Path): IO[Boolean] =
    IO.blocking {
      val zis = new ZipInputStream(new FileInputStream(jarPath.toIO))
      try
        Iterator.continually(zis.getNextEntry)
          .takeWhile(_ != null)
          .exists(_.getName.endsWith(".tasty"))
      finally zis.close()
    }

  private def hasTastyFiles(dir: os.Path): Boolean =
    os.walk.stream(dir).exists(_.ext == "tasty")
}

object IndexManager {
  def apply(
      projectIndex: ProjectIndex,
      dependencyIndex: DependencyIndex,
      bytecodeIndexer: BytecodeIndexer,
  ): IndexManager = new IndexManager(projectIndex, dependencyIndex, bytecodeIndexer)
}
