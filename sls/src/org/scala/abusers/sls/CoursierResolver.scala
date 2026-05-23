package org.scala.abusers.sls

import cats.effect.IO
import coursierapi.Cache
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.Repository

import scala.jdk.CollectionConverters.*

/** Tiny convenience wrapper around `coursierapi.Fetch` used by the few callers in sls that resolve Maven artifacts
  * (presentation-compiler jars, hermetic dependency classpaths, sources jars).
  */
object CoursierResolver {

  def fetchPaths(
      cache: Cache,
      dependencies: Seq[Dependency],
      repositories: Seq[Repository] = Nil,
      classifiers: Set[String] = Set.empty,
  ): IO[List[AbsolutePath]] = IO.blocking {
    Fetch
      .create()
      .withCache(cache)
      .addDependencies(dependencies*)
      .addRepositories(repositories*)
      .addClassifiers(classifiers.toSeq*)
      .fetch()
      .asScala
      .map(f => AbsolutePath(f.toPath))
      .toList
  }
}
