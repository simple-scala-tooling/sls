package org.scala.abusers.sls

import cats.data.OptionT
import cats.effect.IO
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Fallback algorithm to map classfile paths to source directories.
  *
  * Implements the algorithm described in JVM specification (JVMS §4.7.10)
  * to resolve source file locations from classfile information when direct
  * source path information is unavailable.
  *
  * Since we control compilation through BSP, the SourceFile attribute (JVMS §4.7.10)
  * will always be present and includes the full filename with extension.
  */
class ClassFileSourceResolver(rootPath: Path, lspClient: SlsLanguageClient[IO]) {

  private def extractSourceFileName(classFilePath: Path): OptionT[IO, String] = OptionT.fromOption {
    val bytes = Files.readAllBytes(classFilePath)
    val reader = ClassReader(bytes)

    var sourceFileName: Option[String] = None

    // SourceFile attribute (JVMS §4.7.10): contains source filename with extension
    val visitor = new ClassVisitor(Opcodes.ASM9) {
      override def visitSource(source: String, debug: String): Unit = {
        sourceFileName = Option(source)
      }
    }

    reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    sourceFileName
  }

  private def sourceNameFromFullyQualifiedPath(fullyQualifiedPath: String, isJava: Boolean): String = {
    val simpleClassName = fullyQualifiedPath.split('.').last
    val baseName = simpleClassName.takeWhile(_ != '$')
    val ext = if (isJava) "java" else "scala"
    s"$baseName.$ext"
  }

  private def extractPackageName(fullyQualifiedClassName: String): String =
    fullyQualifiedClassName.split('.').dropRight(1).mkString(".")

  /** Find all source files with the given filename
    *
    * Current implementation: Recursively searches the workspace directory tree.
    *
    * Enhancement opportunity: We could extract the build target/project name from the
    * classfile directory structure (e.g., "out/myproject/compile/...") and query only
    * sources from that specific build target via BSP, significantly reducing the search space
    * and improving accuracy.
    *
    * @param sourceFileName The name of the source file to find (e.g., "MyClass.scala")
    * @return IO containing list of paths matching the filename
    */
  private def findSourcesByName(sourceFileName: String): IO[List[Path]] = IO {
    import java.nio.file.{Files, FileVisitOption}
    import scala.jdk.StreamConverters.*

    Files.walk(rootPath, FileVisitOption.FOLLOW_LINKS)
      .toScala(List)
      .filter { path =>
        Files.isRegularFile(path) && path.getFileName.toString == sourceFileName
      }
  }

  /** Refine candidate paths using package structure matching
    *
    * Matches package components in reverse order (innermost to outermost) as per
    * the reference algorithm. Prefers paths with the best package alignment.
    *
    * @param candidates List of candidate source paths
    * @param expectedPackage The expected package path (e.g., "com.example")
    * @return List of best-matched paths based on package structure
    */
  private def refineByPackageStructure(
      candidates: List[Path],
      expectedPackage: String,
  ): List[Path] = candidates match {
    case Nil => Nil
    case single :: Nil => List(single)
    case multiple =>
      val packageParts = if (expectedPackage.nonEmpty) expectedPackage.split('.').toList.reverse else Nil

      val scored = multiple.map { path =>
        val pathParts = path.iterator.asScala.map(_.toString).toList.reverse.tail
        // Match package components from innermost to outermost
        val score = packageParts.zip(pathParts).count { case (pkg, dir) => pkg == dir }
        (path, score)
      }

      val maxScore = scored.map(_._2).maxOption.getOrElse(0)

      if (maxScore > 0) {
        // Return paths with the best package alignment
        scored.filter(_._2 == maxScore).map(_._1)
      } else {
        // If no package match, prefer shorter paths (fewer directory components)
        val minDepth = multiple.map(_.getNameCount).min
        multiple.filter(_.getNameCount == minDepth)
      }
  }

  def resolveSourceFromClassFile(
      classFilePath: String,
      fullyQualifiedClassName: String,
      isJava: Boolean
  ): OptionT[IO, Path] = OptionT(
      for {
        path <- IO.pure(Path.of(classFilePath))
        sourceFileName <- extractSourceFileName(path)
          .getOrElse(sourceNameFromFullyQualifiedPath(fullyQualifiedClassName, isJava))
        packageName = extractPackageName(fullyQualifiedClassName)
        candidates <- findSourcesByName(sourceFileName)
        refined = refineByPackageStructure(candidates, packageName)
      } yield refined match {
        case single :: Nil => Some(single)
        case _             => None
      }
    )
}

object ClassFileSourceResolver {
  def instance(
      rootPath: Path,
      lspClient: SlsLanguageClient[IO],
  ): IO[ClassFileSourceResolver] = {
    IO.pure(new ClassFileSourceResolver(rootPath, lspClient))
  }
}
