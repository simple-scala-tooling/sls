package org.scala.abusers.zincCli

import java.nio.file.Files
import java.nio.file.Path
import scala.util.Random

object OutputJar {
  val random = new Random()
}

class OutputJar(outputDir: Path, tempDir: Path, jarName: String) {
  private val randomId = OutputJar.random.alphanumeric.take(16).mkString
  def temp: Path       = tempDir.resolve(s"$jarName-temp-$randomId.jar")
  def path: Path       = outputDir.resolve(s"$jarName.jar")
}

class OutputJarWithDirTemp(outputDir: Path, tempDir: Path, jarName: String) {
  val METAINF          = "META-INF/"
  val BEST_EFFORT      = "best-effort/"
  private val randomId = OutputJar.random.alphanumeric.take(16).mkString
  def temp: Path       = tempDir.resolve(s"$jarName-temp/")
  def tempJar          = tempDir.resolve(s"$jarName-temp-$randomId.jar")

  /** Jar up the temp dir's best-effort output, keeping only `expectedEntries` (relative paths): the temp dir
    * accumulates across compiles, so products of deleted/renamed sources must be pruned or they ghost in the jar
    * forever. Stray files are deleted from the temp dir too, so they don't come back next compile.
    */
  def cleanAndMoveToFinalDest(expectedEntries: Set[String]): Unit = {
    import sbt.io.syntax.*
    import sbt.io.Path.*

    System.err.println(s"Moving temp jar from $tempJar to final destination $path")
    val tempMetaInfDir = temp.resolve(METAINF).resolve(BEST_EFFORT)
    val allFiles       = (tempMetaInfDir.toFile ** "*")
      .get()
      .filter(_.isFile())
      .pair(relativeTo(tempMetaInfDir.toFile))

    val (fileMappings, stale) = allFiles.partition { case (_, rel) => expectedEntries(rel.replace('\\', '/')) }
    stale.foreach { case (file, _) => sbt.io.IO.delete(file) }

    val manifest = java.util.jar.Manifest()
    sbt.io.IO.jar(fileMappings, tempJar.toFile, manifest, None) // Create jar from temp directory

    Files.move(
      tempJar,
      path,
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
  }

  def path: Path = outputDir.resolve(s"$jarName.jar")
}
