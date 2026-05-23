package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.AbsolutePath
import weaver.*

import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object JarMavenCoordinatesSpec extends SimpleIOSuite {

  private def jarWithEntries(entries: List[(String, Array[Byte])]): IO[AbsolutePath] = IO.blocking {
    val tmp     = os.temp.dir(prefix = "jar-coords-test")
    val jarPath = tmp / "test.jar"
    val zos     = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try
      entries.foreach { case (name, bytes) =>
        zos.putNextEntry(new ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
      }
    finally zos.close()
    AbsolutePath(jarPath.toNIO)
  }

  private def pomProps(group: String, artifact: String, version: String): Array[Byte] =
    s"""groupId=$group
       |artifactId=$artifact
       |version=$version
       |""".stripMargin.getBytes("UTF-8")

  test("reads coordinates from pom.properties under META-INF/maven") {
    for {
      jar <- jarWithEntries(
        List(
          "META-INF/maven/com.example/foo/pom.properties" -> pomProps("com.example", "foo", "1.2.3")
        )
      )
    } yield expect(JarMavenCoordinates.read(jar).contains(MavenCoordinates("com.example", "foo", "1.2.3")))
  }

  test("returns None when no pom.properties is present") {
    for {
      jar <- jarWithEntries(List("META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n".getBytes("UTF-8")))
    } yield expect(JarMavenCoordinates.read(jar).isEmpty)
  }

  test("returns None when pom.properties is missing a coordinate") {
    val partial =
      """groupId=com.example
        |artifactId=foo
        |""".stripMargin.getBytes("UTF-8")
    for {
      jar <- jarWithEntries(List("META-INF/maven/com.example/foo/pom.properties" -> partial))
    } yield expect(JarMavenCoordinates.read(jar).isEmpty)
  }
}
