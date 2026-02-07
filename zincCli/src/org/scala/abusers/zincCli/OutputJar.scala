package org.scala.abusers.zincCli

import java.nio.file.Path
import java.{util => ju}
import ju.UUID
import scala.util.Random
import java.nio.file.Files

object OutputJar {
  val random = new Random()
}

class OutputJar(outputDir: Path, tempDir: Path, jarName: String) {
  private val randomId = OutputJar.random.alphanumeric.take(16).mkString
  def temp: Path = tempDir.resolve(s"$jarName-temp-$randomId.jar")
  def path: Path = outputDir.resolve(s"$jarName.jar")
}

class OutputJarWithDirTemp(outputDir: Path, tempDir: Path, jarName: String) {
  val METAINF = "META-INF/"
  val BEST_EFFORT = "best-effort/"
  private val randomId = OutputJar.random.alphanumeric.take(16).mkString
  def temp: Path = tempDir.resolve(s"$jarName-temp-$randomId/")
  def cleanAndMoveToFinalDest: Unit =
    import sbt.io.syntax.*
    import sbt.io.Path.*

    val tempJar = tempDir.resolve(s"$jarName-temp-$randomId.jar")
    val tempMetaInfDir = temp.resolve(METAINF).resolve(BEST_EFFORT)
    val fileMappings = (tempMetaInfDir.toFile ** "*")
      .get
      .filter(_.isFile())
      .pair(relativeTo(tempMetaInfDir.toFile))

    val manifest = java.util.jar.Manifest()
    sbt.io.IO.jar(fileMappings, tempJar.toFile, manifest, None) // Create jar from temp directory
    sbt.io.IO.delete(temp.toFile) // Remove temporary directory

    Files.move(tempJar, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) // Jedrzeju pamietaj ze te jarki rosna zawartoscia przy kazdej kompilacji

  def path: Path = outputDir.resolve(s"$jarName.jar")
}
