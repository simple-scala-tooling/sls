package org.scala.abusers.sls

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.{Stream, io}
import fs2.io.file.{Files, Path}
import weaver.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

object UnzipUtilsTest extends SimpleIOSuite {

  def createTestJar(): IO[Array[Byte]] = IO {
    val baos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(baos)

    // Create directory entries
    val directories = List(
      "com/",
      "com/example/",
      "com/example/util/",
      "META-INF/"
    )

    directories.foreach { dir =>
      val entry = new ZipEntry(dir)
      entry.setMethod(ZipEntry.STORED)
      entry.setSize(0)
      entry.setCrc(0)
      zos.putNextEntry(entry)
      zos.closeEntry()
    }

    // Create file entries with content
    val files = Map(
      "com/example/util/Helper.java" ->
        """package com.example.util;
          |
          |public class Helper {
          |    public static String getMessage() {
          |        return "Hello from test jar!";
          |    }
          |}""".stripMargin,
      "META-INF/MANIFEST.MF" ->
        """Manifest-Version: 1.0
          |Created-By: UnzipUtilsTest""".stripMargin,
      "com/example/Config.properties" ->
        """app.name=TestApp
          |app.version=1.0""".stripMargin
    )

    files.foreach { case (fileName, content) =>
      val entry = new ZipEntry(fileName)
      val bytes = content.getBytes("UTF-8")
      entry.setSize(bytes.length)
      zos.putNextEntry(entry)
      zos.write(bytes)
      zos.closeEntry()
    }

    zos.close()
    baos.toByteArray()
  }

  test("unzipJar should extract jar contents with proper directory structure") {
    val tempDirResource = Resource.make(Files[IO].createTempDirectory(None, "unzip-test-", None))(Files[IO].deleteRecursively)

    tempDirResource.use { tempDir =>
      for {
        jarBytes <- createTestJar()
        jarInputStream = new ByteArrayInputStream(jarBytes)
        _ <- UnzipUtils.unzipJar(jarInputStream, tempDir)

        // Verify directories were created
        comDirExists <- Files[IO].exists(tempDir / "com")
        exampleDirExists <- Files[IO].exists(tempDir / "com" / "example")
        utilDirExists <- Files[IO].exists(tempDir / "com" / "example" / "util")
        metaInfExists <- Files[IO].exists(tempDir / "META-INF")

        // Verify files were created
        helperFileExists <- Files[IO].exists(tempDir / "com" / "example" / "util" / "Helper.java")
        manifestExists <- Files[IO].exists(tempDir / "META-INF" / "MANIFEST.MF")
        configExists <- Files[IO].exists(tempDir / "com" / "example" / "Config.properties")

        // Verify file contents
        helperContent <- Files[IO].readUtf8(tempDir / "com" / "example" / "util" / "Helper.java").compile.string
        manifestContent <- Files[IO].readUtf8(tempDir / "META-INF" / "MANIFEST.MF").compile.string
        configContent <- Files[IO].readUtf8(tempDir / "com" / "example" / "Config.properties").compile.string
      } yield expect.all(
        comDirExists,
        exampleDirExists,
        utilDirExists,
        metaInfExists,
        helperFileExists,
        manifestExists,
        configExists,
        helperContent.contains("Hello from test jar!"),
        manifestContent.contains("Manifest-Version: 1.0"),
        configContent.contains("app.name=TestApp")
      )
    }
  }

  test("unzipJarFromPath should extract jar from file path") {
    val tempDirResource = Resource.make(Files[IO].createTempDirectory(None, "unzip-test-", None))(Files[IO].deleteRecursively)
    val jarFileResource = Resource.make(Files[IO].createTempFile(None, "test", ".jar", None))(Files[IO].delete)

    (tempDirResource, jarFileResource).tupled.use { case (tempDir, jarPath) =>
      for {
        jarBytes <- createTestJar()
        _ <- Stream.emits(jarBytes).through(Files[IO].writeAll(jarPath)).compile.drain
        _ <- UnzipUtils.unzipJarFromPath(jarPath, tempDir)
        helperFileExists <- Files[IO].exists(tempDir / "com" / "example" / "util" / "Helper.java")
        helperContent <- Files[IO].readUtf8(tempDir / "com" / "example" / "util" / "Helper.java").compile.string
      } yield expect.all(
        helperFileExists,
        helperContent.contains("Hello from test jar!")
      )
    }
  }

  test("unzipJar should handle empty directories correctly") {
    val tempDirResource = Resource.make(Files[IO].createTempDirectory(None, "unzip-test-", None))(Files[IO].deleteRecursively)

    tempDirResource.use { tempDir =>
      for {
        jarBytes <- createTestJar()
        jarInputStream = new ByteArrayInputStream(jarBytes)
        _ <- UnzipUtils.unzipJar(jarInputStream, tempDir)

        comDir = tempDir / "com"
        exampleDir = tempDir / "com" / "example"
        utilDir = tempDir / "com" / "example" / "util"

        comIsDir <- Files[IO].isDirectory(comDir)
        exampleIsDir <- Files[IO].isDirectory(exampleDir)
        utilIsDir <- Files[IO].isDirectory(utilDir)
      } yield expect.all(
        comIsDir,
        exampleIsDir,
        utilIsDir
      )
    }
  }

  test("unzipJar should create target directory if it doesn't exist") {
    val tempDirResource = Resource.make(Files[IO].createTempDirectory(None, "unzip-test-", None))(Files[IO].deleteRecursively)

    tempDirResource.use { tempDir =>
      for {
        jarBytes <- createTestJar()
        nonExistentTarget = tempDir / "nested" / "target"
        jarInputStream = new ByteArrayInputStream(jarBytes)
        _ <- UnzipUtils.unzipJar(jarInputStream, nonExistentTarget)
        targetExists <- Files[IO].exists(nonExistentTarget)
        fileExists <- Files[IO].exists(nonExistentTarget / "com" / "example" / "util" / "Helper.java")
      } yield expect.all(
        targetExists,
        fileExists
      )
    }
  }
}
