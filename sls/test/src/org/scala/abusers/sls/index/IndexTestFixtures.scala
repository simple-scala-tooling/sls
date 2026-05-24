package org.scala.abusers.sls.index

import org.scala.abusers.sls.AbsolutePath

import java.nio.file.Paths

/** Coordinates and resolved JAR paths for the index test fixtures.
  *
  * System properties are injected by `sls.test.forkArgs` in `build.mill`, which also publishes the fixtures to `~/.m2`
  * (`publishM2LocalCached`) before tests fork. Pattern mirrors the VirtusLab/cellar fixture approach.
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
    group = require("sls.test.crossProducerGroup"),
    artifact = require("sls.test.crossProducerArtifact"),
    version = require("sls.test.crossProducerVersion"),
  )

  lazy val tastyIndexerCoord: Coord = Coord(
    group = require("sls.test.tastyIndexerGroup"),
    artifact = require("sls.test.tastyIndexerArtifact"),
    version = require("sls.test.tastyIndexerVersion"),
  )

  lazy val crossProducerJar: AbsolutePath     = crossProducerCoord.jarPath(localM2)
  lazy val tastyIndexerJar: AbsolutePath      = tastyIndexerCoord.jarPath(localM2)
  lazy val crossProducerLibJSrc: AbsolutePath =
    AbsolutePath(Paths.get(require("sls.test.crossProducerLibJSrc")))

  /** Test-only canonical-id builder. Parses a dotted name into a [[SymbolId]] under the assumption that all but the
    * final segment are packages (no nested classes/owners) and the final segment is the symbol name. Use the explicit
    * overload for owner/member structure when needed.
    *
    * tid("crossproducer.Lib") == SymbolId.tpe(List("crossproducer"), Nil, "Lib") tid("crossproducer.pkg.Foo") ==
    * SymbolId.tpe(List("crossproducer", "pkg"), Nil, "Foo")
    */
  def tid(dotted: String): SymbolId = {
    val parts = dotted.split('.').toList.filter(_.nonEmpty)
    parts match {
      case Nil       => SymbolId.tpe(Nil, Nil, "")
      case List(one) => SymbolId.tpe(Nil, Nil, one)
      case many      => SymbolId.tpe(many.init, Nil, many.last)
    }
  }

  /** Term-id counterpart to [[tid]]: the final segment is treated as a term (method/val/etc.) and the segment before it
    * as its owning class/object.
    *
    * mid("crossproducer.Lib.compute") == SymbolId.term(List("crossproducer"), List("Lib"), "compute")
    */
  def mid(dotted: String): SymbolId = {
    val parts = dotted.split('.').toList.filter(_.nonEmpty)
    parts match {
      case Nil        => SymbolId.term(Nil, Nil, "")
      case List(one)  => SymbolId.term(Nil, Nil, one)
      case List(a, b) => SymbolId.term(Nil, List(a), b)
      case many       => SymbolId.term(many.init.init, List(many.init.last), many.last)
    }
  }
}
