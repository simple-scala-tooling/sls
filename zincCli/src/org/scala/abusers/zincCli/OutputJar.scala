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

  def cleanAndMoveToFinalDest: Unit = {
    import sbt.io.syntax.*
    import sbt.io.Path.*

    System.err.println(s"Moving temp jar from $tempJar to final destination $path")
    val tempMetaInfDir = temp.resolve(METAINF).resolve(BEST_EFFORT)
    val fileMappings   = (tempMetaInfDir.toFile ** "*")
      .get()
      .filter(_.isFile())
      .pair(relativeTo(tempMetaInfDir.toFile))

    val manifest = java.util.jar.Manifest()
    sbt.io.IO.jar(fileMappings, tempJar.toFile, manifest, None) // Create jar from temp directory
    // sbt.io.IO.delete(temp.toFile) // Remove temporary directory

    Files.move(
      tempJar,
      path,
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    ) // Jedrzeju pamietaj ze te jarki rosna zawartoscia przy kazdej kompilacji
  }

  def path: Path = outputDir.resolve(s"$jarName.jar")
}
