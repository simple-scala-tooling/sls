package org.scala.abusers.zincCli

import sbt.internal.inc.*
import java.nio.file.Path
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.Cache
import coursierapi.MavenRepository
import scala.jdk.CollectionConverters.*
import xsbti.compile.ZincCompilerUtil
import sbt.internal.inc.classpath.ClassLoaderCache
import java.util.concurrent.ConcurrentHashMap
import sbt.internal.inc.classpath.ClasspathUtil
import xsbti.compile.ClasspathOptionsUtil
import java.{util => ju}
import java.io.File

class ZincClassLoaderCaches { // rewrite this to mapref, copy code from Scastie
  lazy val classLoaderCache = Some(new ClassLoaderCache(null: ClassLoader))

  private val classLoaders: ConcurrentHashMap[Seq[Path], ClassLoader] = new ConcurrentHashMap

  // null by default, so it's not parented to the current classloader, because we want isolation
  def classLoaderFor(scalaClasspath: Seq[Path], parent: ClassLoader): ClassLoader =
    classLoaders.computeIfAbsent(
      scalaClasspath,
      cp => ClasspathUtil.toLoader(cp.toList, parent)
    )
}

object ScalaInstanceProvider {
  private val coursierCache = Cache.create() // .withLogger TODO No completions here
  private val classloaderCache = ZincClassLoaderCaches()
  private val classLoaders: ConcurrentHashMap[String, AnalyzingCompiler] = new ConcurrentHashMap

  private def compiledBridgeDefinition(version: String) = {
    val bridgeName = if version.startsWith("3") then "scala3-sbt-bridge" else "scala2-sbt-bridge"
    Dependency.of("org.scala-lang", bridgeName, version)
  }

  private def scalaDependencies(version: String) =
    if version.startsWith("3") then Dependency.of("org.scala-lang", "scala3-compiler_3", version)
    else Dependency.of("org.scala-lang", "scala-compiler", version)

  def getScalaInstance(version: String): AnalyzingCompiler = { // TODO: Migrate to IO
    def getScalaInstance0 = {
      // val version = "3.7.4"
      val dep = compiledBridgeDefinition(version)
      val downloaded = Fetch
        .create()
        .withCache(coursierCache)
        .addDependencies(dep)
        .addRepositories(MavenRepository.of("https://central.sonatype.com/repository/maven-snapshots/"))
        .fetch()
        .asScala
        .toList
        .find(_.getName().contains("sbt-bridge"))
        .getOrElse(sys.error(s"Did not download sbt-bridge for $version"))

      val scalaClasspath = {
        Fetch
          .create()
          .withCache(coursierCache)
          .addDependencies(scalaDependencies(version))
          .addRepositories(MavenRepository.of("https://central.sonatype.com/repository/maven-snapshots/"))
          .fetch()
          .asScala
          .toList
          .map(_.toPath)
      }

      val scalaLibraryJars = scalaClasspath.filter(_.toString.contains("scala-library-"))
      val scalaCompilerJar = scalaClasspath.filter(_.toString.contains("compiler")).headOption

      assert(scalaCompilerJar.nonEmpty)

      val others = (scalaClasspath diff scalaLibraryJars) diff scalaCompilerJar.toList

      val jarsToLoad: Vector[Path] =
        (scalaCompilerJar.toVector ++ scalaLibraryJars ++ others)

      val jarsToLoadWithoutLibrary = jarsToLoad diff scalaLibraryJars

      val loaderLibraryOnly = classloaderCache.classLoaderFor(scalaLibraryJars, ScalaInstanceTopLoader.topClassLoader)
      val loader = classloaderCache.classLoaderFor(jarsToLoadWithoutLibrary, loaderLibraryOnly)

      val allJars = jarsToLoad.map(_.toFile).toArray

      val scalaInstance = new ScalaInstance(
        version = version,
        loader = loader,
        loaderCompilerOnly = loader,
        loaderLibraryOnly = ClasspathUtil.rootLoader,
        libraryJars = scalaLibraryJars.map(_.toFile).toArray,
        compilerJars = allJars,
        allJars = allJars,
        explicitActual = None
      )

      val compilerBridgeProvider = ZincCompilerUtil.constantBridgeProvider(scalaInstance, downloaded)

      AnalyzingCompiler(
        scalaInstance, compilerBridgeProvider, ClasspathOptionsUtil.javac(false), _ => (), classloaderCache.classLoaderCache
      )
    }

    classLoaders.computeIfAbsent(
      version,
      _ => getScalaInstance0
    )

  }
}
