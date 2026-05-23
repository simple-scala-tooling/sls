package org.scala.abusers.sls.index

import org.scala.abusers.sls.AbsolutePath

import java.nio.file.Paths

/** Coordinates and resolved JAR paths for the index test fixtures.
  *
  * System properties are injected by `sls.test.forkArgs` in `build.mill`, which also
  * publishes the fixtures to `~/.m2` (`publishM2LocalCached`) before tests fork. Pattern
  * mirrors the VirtusLab/cellar fixture approach.
  */
object IndexTestFixtures {

  private def require(key: String): String = {
    val v = System.getProperty(key)
    assert(v != null, s"System property '$key' is not set — run tests via './mill sls.test'.")
    v
  }

  final case class Coord(group: String, artifact: String, version: String) {
    /** Absolute path of the published JAR inside the local M2 repository. */
    def jarPath(localM2: String): AbsolutePath = {
      val groupDir = group.replace('.', '/')
      AbsolutePath(Paths.get(localM2, groupDir, artifact, version, s"$artifact-$version.jar"))
    }
  }

  lazy val localM2: String = require("sls.test.localM2")

  lazy val crossProducerCoord: Coord = Coord(
    group    = require("sls.test.crossProducerGroup"),
    artifact = require("sls.test.crossProducerArtifact"),
    version  = require("sls.test.crossProducerVersion"),
  )

  lazy val tastyIndexerCoord: Coord = Coord(
    group    = require("sls.test.tastyIndexerGroup"),
    artifact = require("sls.test.tastyIndexerArtifact"),
    version  = require("sls.test.tastyIndexerVersion"),
  )

  lazy val crossProducerJar: AbsolutePath  = crossProducerCoord.jarPath(localM2)
  lazy val tastyIndexerJar: AbsolutePath   = tastyIndexerCoord.jarPath(localM2)
  lazy val crossProducerLibJSrc: AbsolutePath =
    AbsolutePath(Paths.get(require("sls.test.crossProducerLibJSrc")))
}
