package org.scala.abusers.sls

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{Files, Path}
import java.io.{ByteArrayInputStream, InputStream}
import java.util.zip.{ZipEntry, ZipInputStream}

object UnzipUtils {

  def unzipJar(jarInputStream: InputStream, targetDirectory: Path): IO[Unit] =
    Files[IO].createDirectories(targetDirectory) *>
      Stream
        .bracket(IO(new ZipInputStream(jarInputStream)))(zis => IO(zis.close()))
        .flatMap { zipInputStream =>
          Stream
            .unfoldEval(zipInputStream) { zis =>
              IO.blocking { // Check to wrap this in fs2 streams ?
                Option(zis.getNextEntry()).map { entry =>
                  val entryData = readEntryBytes(zis, entry)
                  ((entry, entryData), zis)
                }
              }
            }
            .evalMap { case (entry, data) =>
              processZipEntry(entry, data, targetDirectory)
            }
        }
        .compile
        .drain

  def unzipJarFromPath(jarPath: Path, targetDirectory: Path): IO[Unit] =
    Files[IO].exists(targetDirectory).flatMap {
      case true =>
        cats.effect.std.Console[IO].errorln(s"Target directory $targetDirectory already exists. Aborting unzip operation.") *> IO.unit
      case false => Files[IO]
        .readAll(jarPath)
        .through(fs2.io.toInputStream[IO])
        .evalMap(unzipJar(_, targetDirectory))
        .compile
        .drain
    }


  private def readEntryBytes(zis: ZipInputStream, entry: ZipEntry): Array[Byte] = {
    if (entry.isDirectory) {
      Array.empty[Byte]
    } else {
      val buffer = new Array[Byte](8192)
      val result = scala.collection.mutable.ArrayBuffer[Byte]()
      var bytesRead = zis.read(buffer)
      while (bytesRead != -1) {
        result ++= buffer.take(bytesRead)
        bytesRead = zis.read(buffer)
      }
      result.toArray
    }
  }

  private def processZipEntry(entry: ZipEntry, data: Array[Byte], targetDirectory: Path): IO[Unit] = {
    val entryPath = targetDirectory / Path(entry.getName)

    if (entry.isDirectory) {
      Files[IO].createDirectories(entryPath)
    } else {
      // Ensure parent directories exist
      Files[IO].createDirectories(entryPath.parent.getOrElse(targetDirectory)) *>
        Stream
          .emits(data)
          .through(Files[IO].writeAll(entryPath))
          .compile
          .drain
    }
  }
}
