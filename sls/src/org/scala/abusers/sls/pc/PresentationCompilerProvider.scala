package org.scala.abusers.pc

import bsp.BuildTargetIdentifier
import cats.effect.IO
import com.evolution.scache.Cache as SCache
import com.evolution.scache.ExpiringCache
import coursierapi.*
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.CoursierResolver
import org.scala.abusers.sls.ScalaBuildTargetInformation
import org.scala.abusers.sls.ScalaBuildTargetInformation.*
import org.scala.abusers.sls.SynchronizedState

import java.net.URLClassLoader
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.meta.pc.RawPresentationCompiler

trait PresentationCompilerProvider {
  def get(info: ScalaBuildTargetInformation)(using SynchronizedState): IO[RawPresentationCompiler]
  def invalidateCompilers(): IO[Unit]
}

private class CachingPresentationCompilerProvider(
    serviceLoader: BlockingServiceLoader,
    compilers: SCache[IO, BuildTargetIdentifier, RawPresentationCompiler],
    versionOverride: PcVersionOverride,
) extends PresentationCompilerProvider {
  private val cache = Cache.create() // .withLogger TODO No completions here

  private def fetchPresentationCompilerJars(scalaVersion: ScalaVersion): IO[Seq[AbsolutePath]] = {
    val dep = Dependency.of(
      Module.of("org.scala-lang", "scala3-presentation-compiler_3"),
      scalaVersion.value,
    )
    CoursierResolver.fetchPaths(
      cache,
      Seq(dep),
      repositories = Seq(MavenRepository.of("https://central.sonatype.com/repository/maven-snapshots/")),
    )
  }

  // FIXME: We need to implement logic that invalidates all dependent nodes
  def invalidateCompilers(): IO[Unit] = compilers.clear.void

  private def freshPresentationCompilerClassloader(
      projectClasspath: Seq[AbsolutePath],
      compilerClasspath: Seq[AbsolutePath],
  ): IO[URLClassLoader] =
    IO.blocking {
      val fullClasspath    = compilerClasspath ++ projectClasspath
      val urlFullClasspath = fullClasspath.map(_.toFile.toURI.toURL)
      URLClassLoader(urlFullClasspath.toArray)
    }

  private def createPC(scalaVersion: ScalaVersion, projectClasspath: List[AbsolutePath], scalacOptions: List[String]) =
    for {
      compilerClasspath <- fetchPresentationCompilerJars(scalaVersion)
      classloader       <- freshPresentationCompilerClassloader(projectClasspath, compilerClasspath)
      pc <- serviceLoader.load(classOf[RawPresentationCompiler], PresentationCompilerProvider.classname, classloader)
      scalacOptions0 = scalacOptions ++ Seq("-Ywith-best-effort-tasty", "-Ybest-effort")
      _ <- IO.consoleForIO.error(
        s"Creating presentation compiler with classpath: ${projectClasspath.map(_.toNioPath.toString).mkString(", ")} and options: ${scalacOptions0.mkString(" ")}"
      )
    } yield pc.newInstance("pc-id-replace", projectClasspath.map(_.toNioPath).asJava, scalacOptions0.toList.asJava)

  def get(info: ScalaBuildTargetInformation)(using SynchronizedState): IO[RawPresentationCompiler] = {
    val pcVersion = versionOverride.resolve(info.displayName, info.scalaVersion)
    val logOverride = IO.whenA(pcVersion.value != info.scalaVersion.value) {
      IO.consoleForIO.error(
        s"PC version override for module '${info.displayName}': ${info.scalaVersion.value} -> ${pcVersion.value}"
      )
    }
    logOverride *> compilers.getOrUpdate(info.buildTarget.id)(createPC(pcVersion, info.classpath, info.compilerOptions))
  }
}

object PresentationCompilerProvider {
  val classname = "dotty.tools.pc.RawScalaPresentationCompiler"

  /** The provider must live inside the cache's Resource scope: the previous `.use(cache => IO(provider))` released
    * the cache immediately, letting the provider escape with a released cache and leaking scache's background expiry
    * fiber past the caller's lifetime.
    */
  def instance: cats.effect.Resource[IO, PresentationCompilerProvider] =
    for {
      serviceLoader <- cats.effect.Resource.eval(BlockingServiceLoader.instance)
      cache         <- SCache
        .expiring[IO, BuildTargetIdentifier, RawPresentationCompiler]( // we will need to move this out because other services will want to manage the state of the cache and invalidate when configuration changes also this shoul be ModuleFingerprint or something like that
          ExpiringCache.Config(expireAfterRead = 5.minutes),
          None,
        )
    } yield CachingPresentationCompilerProvider(serviceLoader, cache, PcVersionOverride.fromEnv)
}

opaque type ScalaVersion = String

extension (scalaVersion: ScalaVersion) def value: String = scalaVersion

object ScalaVersion {
  private val versionRegex = "\\d+(?:[_.-]\\d+)*".r
  private val separators   = Array('.', '-', '_')

  def apply(scalaVersion: String): ScalaVersion = scalaVersion
  given Ordering[ScalaVersion]                  = new Ordering[ScalaVersion] {
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
