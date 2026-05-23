package org.scala.abusers
package sls.index

import cats.effect.IO
import coursierapi.Cache
import coursierapi.Dependency
import coursierapi.Module
import org.scala.abusers.csp.CompileOutput
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.CoursierResolver
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
    depIndexCache: DepIndexCache = DepIndexCache.default,
) {
  private val logger        = LoggerFactory.getLogger(this.getClass)
  private val coursierCache = Cache.create()

  def indexDependencies(targets: Set[ScalaBuildTargetInformation]): IO[Unit] = {
    val projectDirs = targets.flatMap(t => Set(t.classesDir, t.classJarPath))

    // Each target's classpath is internally consistent (built by the build tool). The union across
    // targets typically isn't — e.g. a Mill project can carry both scala3-library_3-3.7.4 (Mill's own
    // runtime) and 3.8.x (user code) — and feeding a mixed classpath to dotc's TASTy unpickler makes
    // it resolve a symbol against one stdlib while the TASTy was pickled against another, producing
    // stub denotations and assertion failures in erasure. So we pick one target per jar and use that
    // target's classpath. Any target containing the jar will do, since each is self-consistent.
    val jarToCp = {
      val m = scala.collection.mutable.LinkedHashMap.empty[AbsolutePath, List[AbsolutePath]]
      for {
        target <- targets
        jar    <- target.classpath
      } {
        val isDep =
          jar.toNioPath.toString.endsWith(".jar") &&
            !projectDirs.contains(jar) &&
            jar.exists
        if isDep && !m.contains(jar) then m(jar) = target.classpath
      }
      m.toList
    }

    val concurrency = Runtime.getRuntime.availableProcessors()
    fs2.Stream
      .emits(jarToCp)
      .parEvalMapUnordered(concurrency) { case (jar, cp) => indexJarSafely(jar, cp) }
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

    def runJavaIndex(sourcesJar: AbsolutePath, cp: List[AbsolutePath]): IO[Boolean] =
      depIndexCache.hashJar(sourcesJar.toNioPath).flatMap { sha =>
        depIndexCache.lookup(sha).flatMap {
          case Some(cached) =>
            logger.info(s"Dep index cache hit for $jar (sha=$sha, ${cached.size} symbols)")
            publish(cached)
          case None =>
            runIndexer(sourcesJar, cp)
              .flatTap(depIndexCache.store(sha, _))
              .flatMap(publish)
        }
      }

    def indexViaJavaSources: IO[Boolean] =
      resolveHermeticDep(jarPath, withSources = true).flatMap {
        case Some(HermeticResolution(cp, Some(sourcesJar))) => runJavaIndex(sourcesJar, cp)
        case _                                              => IO.pure(false)
      }

    (for {
      hasTasty <- jarContainsTasty(jarPath)
      _        <-
        if hasTasty then {
          val indexer = TastyIndexer("dependency")
          // Type-check the jar's TASTy against the classpath it was *pickled against*, not the
          // project's runtime classpath. They are usually different: build tools resolve version
          // conflicts to the newest version, so the project pulls (say) lib Y 1.1 while jar X's
          // TASTy was pickled against Y 1.0. Reading X's TASTy with Y 1.1 on the classpath doesn't
          // crash (1.1 is a superset), but overload resolution / inherited-member iteration /
          // implicit search all happen at TASTy-read time against the 1.1 view — so the indexed
          // references silently drift to symbols X's author never intended. Coursier resolves the
          // jar's POM to reconstruct the original compile-time view. Falls back to the project
          // classpath when the jar has no Maven coords (e.g. Mill-internal jars).
          resolveHermeticDep(jarPath, withSources = false)
            .map(_.map(_.classpath).getOrElse(classpath))
            .flatMap { cp =>
              indexer.indexJar(jarPath, cp).flatMap { results =>
                val symbols = results.values.flatMap(_._1).toList
                dependencyIndex.addJar(jar, symbols)
              }
            }
            .handleErrorWith { e =>
              logger.warn(s"TASTy indexing failed for $jar, falling back to bytecode: ${e.getMessage}")
              indexViaBytecode
            }
        } else
          indexViaJavaSources
            .handleErrorWith { e =>
              logger.error(s"Java source indexing failed for $jar, falling back to bytecode", e)
              IO.pure(false)
            }
            .flatMap { indexed =>
              if indexed then IO.unit
              else indexViaBytecode
            }
    } yield ()).handleError(e => logger.error(s"Failed to index JAR $jar", e))
  }

  /** Resolve the jar's own transitive Maven dependencies via coursier. Returns `Some(_)` only when the jar carries
    * `pom.properties` AND coursier resolves successfully — i.e. the typing classpath is hermetic (a function of the jar
    * alone). When `withSources` is set, the same fetch also pulls the dep's sources jar via the `sources` classifier;
    * `sourcesJar` is populated when one was actually published for this dep. Returns `None` when there are no Maven
    * coords or coursier fails, signaling the caller to use the project classpath and skip caching.
    */
  private def resolveHermeticDep(jarPath: AbsolutePath, withSources: Boolean): IO[Option[HermeticResolution]] =
    IO.blocking(JarMavenCoordinates.read(jarPath)).flatMap {
      case None =>
        IO(logger.debug(s"No Maven coordinates in $jarPath, will use project classpath (no cache)")).as(None)
      case Some(coords) =>
        // TODO: only Maven Central is consulted. Projects pulling deps from snapshot or private repos will fail
        // resolution and fall through to the project-CP path (also bypassing the cache). Thread the project's
        // repository list through here when we have access to it.
        val dep         = Dependency.of(Module.of(coords.groupId, coords.artifactId), coords.version)
        val classifiers = if withSources then Set("sources") else Set.empty[String]
        CoursierResolver.fetchPaths(coursierCache, Seq(dep), classifiers = classifiers).attempt.flatMap {
          case Right(files) =>
            val sourcesName = s"${coords.artifactId}-${coords.version}-sources.jar"
            val sourcesJar  = files.find(_.toNioPath.getFileName.toString == sourcesName)
            val cp          = files.filterNot(_.toNioPath.getFileName.toString.endsWith("-sources.jar"))
            IO.pure(Some(HermeticResolution(cp, sourcesJar)))
          case Left(e) =>
            IO(logger.warn(s"Coursier resolution failed for $coords ($jarPath), will use project classpath", e))
              .as(None)
        }
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

  private case class HermeticResolution(classpath: List[AbsolutePath], sourcesJar: Option[AbsolutePath])
}
