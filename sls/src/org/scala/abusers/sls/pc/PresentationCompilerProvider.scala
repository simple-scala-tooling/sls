package org.scala.abusers.pc

import bsp.BuildTargetIdentifier
import cats.effect.IO
import com.evolution.scache.Cache as SCache
import com.evolution.scache.ExpiringCache
import coursierapi.*
import org.scala.abusers.sls.ScalaBuildTargetInformation
import org.scala.abusers.sls.ScalaBuildTargetInformation.*
import os.Path

import java.net.URLClassLoader
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.meta.pc.PresentationCompiler

class PresentationCompilerProvider(
    serviceLoader: BlockingServiceLoader,
    compilers: SCache[IO, BuildTargetIdentifier, PresentationCompiler],
) {
  private val cache = Cache.create() // .withLogger TODO No completions here

  private def fetchPresentationCompilerJars(scalaVersion: ScalaVersion): IO[Seq[os.Path]] = IO.blocking {
    val dep = Dependency.of(
      Module.of("org.scala-lang", "scala3-presentation-compiler_3"),
      scalaVersion.value,
    )

    Fetch
      .create()
      .withCache(cache)
      .addDependencies(dep)
      .addRepositories( /* load from user config */ )
      .fetch()
      .asScala
      .map(os.Path.apply)
      .toList

  }

  private def freshPresentationCompilerClassloader(
      projectClasspath: Seq[os.Path],
      compilerClasspath: Seq[os.Path],
  ): IO[URLClassLoader] =
    IO.blocking {
      val fullClasspath    = compilerClasspath ++ projectClasspath
      val urlFullClasspath = fullClasspath.map(_.toIO.toURL)
      URLClassLoader(urlFullClasspath.toArray)
    }

  private def createPC(scalaVersion: ScalaVersion, projectClasspath: List[Path], scalacOptions: List[String]) =
    for {
      compilerClasspath <- fetchPresentationCompilerJars(scalaVersion)
      classloader       <- freshPresentationCompilerClassloader(projectClasspath, compilerClasspath)
      pc <- serviceLoader.load(classOf[PresentationCompiler], PresentationCompilerProvider.classname, classloader)
    } yield pc.newInstance("random", projectClasspath.map(_.toNIO).asJava, scalacOptions.asJava)

  def get(info: ScalaBuildTargetInformation): IO[PresentationCompiler] =
    compilers.getOrUpdate(info.buildTarget.id)(createPC(info.scalaVersion, info.classpath, info.compilerOptions))
}

object PresentationCompilerProvider {
  val classname = "dotty.tools.pc.ScalaPresentationCompiler"

  def instance: IO[PresentationCompilerProvider] =
    for {
      serviceLoader <- BlockingServiceLoader.instance
      pcProvider <- SCache
        .expiring[IO, BuildTargetIdentifier, PresentationCompiler]( // we will need to move this out because other services will want to manage the state of the cache and invalidate when configuration changes also this shoul be ModuleFingerprint or something like that
          ExpiringCache.Config(expireAfterRead = 5.minutes),
          None,
        )
        .use(cache => IO(PresentationCompilerProvider(serviceLoader, cache)))
    } yield pcProvider
}

opaque type ScalaVersion = String

extension (scalaVersion: ScalaVersion) def value: String = scalaVersion

object ScalaVersion {
  private val versionRegex = "\\d+(?:[_.-]\\d+)*".r
  private val separators   = Array('.', '-', '_')

  def apply(scalaVersion: String): ScalaVersion = scalaVersion
  given Ordering[ScalaVersion] = new Ordering[ScalaVersion] {
    def compareParts(x: String, y: String): Int =
      (x.headOption, y.headOption) match {
        case (Some(c1), Some(c2)) if c1.isDigit && c2.isDigit =>
          val match1 = versionRegex.findFirstIn(x).get
          val match2 = versionRegex.findFirstIn(y).get

          val parts1 = match1.split(separators)
          val parts2 = match2.split(separators)

          val comparisonResult = (parts1 zip parts2).find { case (xs, ys) => xs != ys } match {
            case Some((x, y)) => x.toInt compare y.toInt
            case None         => parts1.length compare parts2.length
          }

          if comparisonResult == 0 then compareParts(x.drop(match1.length max 1), y.drop(match2.length max 1))
          else comparisonResult
        case (Some(c1), Some(c2)) =>
          val comparisonResult = c1 compare c2
          if comparisonResult == 0 then compareParts(x.tail, y.tail) else comparisonResult
        case _ => x compare y
      }

    override def compare(x: ScalaVersion, y: ScalaVersion): Int =
      compareParts(x.value, y.value)
  }
}
