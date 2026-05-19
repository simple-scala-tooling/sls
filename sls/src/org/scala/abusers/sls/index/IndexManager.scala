package org.scala.abusers
package sls.index

import cats.effect.IO
import coursierapi.Cache
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.Module
import org.scala.abusers.csp.CompileOutput
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.ScalaBuildTargetInformation
import org.scala.abusers.sls.ScalaBuildTargetInformation.*
import org.scala.abusers.sls.SourceUri
import org.slf4j.LoggerFactory

import java.io.FileInputStream
import java.util.zip.ZipInputStream
import scala.jdk.CollectionConverters.*

case class IndexManager(
    projectIndex: ProjectIndex,
    dependencyIndex: DependencyIndex,
    bytecodeIndexer: BytecodeIndexer,
    depIndexCache: DepIndexCache = DepIndexCache.default,
) {
  private val logger        = LoggerFactory.getLogger(this.getClass)
  private val coursierCache = Cache.create()

  def indexDependencies(targets: Set[ScalaBuildTargetInformation]): IO[Unit] = {
    val projectDirs  = targets.flatMap(t => Set(t.classesDir, t.classJarPath))
    val allClasspath = targets.flatMap(_.classpath).toList.distinct
    val depJars      = allClasspath
      .filter { p =>
        p.toNioPath.toString.endsWith(".jar") && !projectDirs.contains(p)
      }
      .filter(_.exists)

    val concurrency = Runtime.getRuntime.availableProcessors()
    fs2.Stream
      .emits(depJars)
      .parEvalMapUnordered(concurrency)(jar => indexJarSafely(jar, allClasspath))
      .compile
      .drain
  }

  /** Index the JDK source archive (`$JAVA_HOME/lib/src.zip`) so completion / hover / find-references work on JDK types.
    * The src.zip is treated like any other dependency source jar — typed against an empty CP (JDK types only reference
    * each other) and cached by SHA-256.
    */
  def indexJdkSources(): IO[Unit] =
    IO.blocking(JdkSources.find())
      .flatMap {
        case None =>
          IO(logger.info("No JDK sources found ($JAVA_HOME/lib/src.zip absent), skipping JDK indexing"))
        case Some(srcZip) =>
          val jar = srcZip.toNioPath.toString
          depIndexCache.hashJar(srcZip.toNioPath).flatMap { sha =>
            depIndexCache.lookup(sha).flatMap {
              case Some(cached) =>
                IO(logger.info(s"JDK sources cache hit ($jar, ${cached.size} symbols)")) *>
                  dependencyIndex.addJar(jar, cached)
              case None =>
                val parallelism = Runtime.getRuntime.availableProcessors()
                IO(
                  logger.info(s"Indexing JDK sources from $jar (parallelism=$parallelism) — this may take a moment")
                ) *>
                  JavaIndexer
                    .forJdk(jar)
                    .indexJarEntries(srcZip, Nil, parallelism)
                    .flatMap { results =>
                      val symbols = results.values.flatMap(_._1).toList
                      IO(logger.info(s"JDK sources indexed: ${symbols.size} symbols, caching")) *>
                        depIndexCache.store(sha, symbols) *>
                        dependencyIndex.addJar(jar, symbols)
                    }
            }
          }
      }
      .handleError(e => logger.error("JDK source indexing failed", e))

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

    def runIndexer(sourcesJar: AbsolutePath, cp: List[AbsolutePath]): IO[List[IndexedSymbol]] =
      JavaIndexer
        .forDependency(jar)
        .indexJarEntries(sourcesJar, cp)
        .map(_.values.flatMap(_._1).toList)

    def publish(symbols: List[IndexedSymbol]): IO[Boolean] =
      if symbols.isEmpty then IO.pure(false)
      else dependencyIndex.addJar(jar, symbols).as(true)

    def indexViaJavaSources(sourcesJar: AbsolutePath): IO[Boolean] =
      resolveHermeticLibCp(jarPath).flatMap {
        case Some(hermeticCp) =>
          depIndexCache.hashJar(sourcesJar.toNioPath).flatMap { sha =>
            depIndexCache.lookup(sha).flatMap {
              case Some(cached) =>
                logger.info(s"Dep index cache hit for $jar (sha=$sha, ${cached.size} symbols)")
                publish(cached)
              case None =>
                runIndexer(sourcesJar, hermeticCp)
                  .flatTap(depIndexCache.store(sha, _))
                  .flatMap(publish)
            }
          }
        case None =>
          runIndexer(sourcesJar, classpath).flatMap(publish)
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

  /** Resolve the jar's own transitive Maven dependencies via coursier. Returns `Some(cp)` only when the jar carries
    * `pom.properties` AND coursier resolves successfully — i.e. the typing classpath is hermetic (a function of the jar
    * alone). Returns `None` otherwise, signaling the caller to use the project classpath and skip caching.
    */
  private def resolveHermeticLibCp(jarPath: AbsolutePath): IO[Option[List[AbsolutePath]]] =
    IO.blocking(JarMavenCoordinates.read(jarPath)).flatMap {
      case None =>
        IO(logger.debug(s"No Maven coordinates in $jarPath, will use project classpath (no cache)")).as(None)
      case Some(coords) =>
        IO.blocking {
          // TODO: only Maven Central is consulted. Projects pulling deps from snapshot or private repos will fail
          // resolution and fall through to the project-CP path (also bypassing the cache). Thread the project's
          // repository list through here via `.withRepositories(...)` when we have access to it.
          Fetch
            .create()
            .withCache(coursierCache)
            .addDependencies(Dependency.of(Module.of(coords.groupId, coords.artifactId), coords.version))
            .fetch()
            .asScala
            .map(f => AbsolutePath(f.toPath))
            .toList
        }.attempt
          .flatMap {
            case Right(cp) => IO.pure(Some(cp))
            case Left(e)   =>
              IO(logger.warn(s"Coursier resolution failed for $coords ($jarPath), will use project classpath", e))
                .as(None)
          }
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
