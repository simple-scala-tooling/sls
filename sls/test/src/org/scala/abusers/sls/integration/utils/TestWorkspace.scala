package org.scala.abusers.sls.integration.utils

import cats.effect.IO
import cats.effect.Resource
import fs2.io.file.Files
import fs2.io.file.Path

import java.net.URI

case class TestWorkspace(
    root: Path,
    uri: URI,
    sourceFiles: Map[String, Path]
) {
  def rootUri: String = uri.toString

  def getSourceFile(name: String): Option[Path] = sourceFiles.get(name)

  def getSourceFileUri(name: String): Option[String] =
    getSourceFile(name).map(_.toNioPath.toUri.toString)
}

object TestWorkspace {

  def withSimpleScalaProject: Resource[IO, TestWorkspace] = {
    for {
      tempDir <- Files[IO].tempDirectory(None, "test-workspace-", None)
      _       <- createMillVersion(tempDir).toResource
      _       <- createBuildMill(tempDir).toResource
      _       <- createSourceFiles(tempDir).toResource
      _       <- copyMillExecutable(tempDir).toResource
      sourceFiles = Map(
        "Main.scala" -> tempDir / "app" / "src" / "Main.scala",
        "Utils.scala" -> tempDir / "app" / "src" / "Utils.scala"
      )
    } yield TestWorkspace(tempDir, tempDir.toNioPath.toUri, sourceFiles)
  }

  def withMultiModuleProject: Resource[IO, TestWorkspace] = {
    for {
      tempDir <- Files[IO].tempDirectory(None, "test-multi-", None)
      _       <- createMillVersion(tempDir).toResource
      _       <- createMultiModuleBuild(tempDir).toResource
      _       <- createMultiModuleSource(tempDir).toResource
      _       <- copyMillExecutable(tempDir).toResource
      sourceFiles = Map(
        "core/Domain.scala" -> tempDir / "core" / "src" / "Domain.scala",
        "app/Main.scala" -> tempDir / "app" / "src" / "Main.scala"
      )
    } yield TestWorkspace(tempDir, tempDir.toNioPath.toUri, sourceFiles)
  }

  private def createMillVersion(root: Path): IO[Unit] = {
    // Use the same mill version as the main project
    val millVersion = "0.12.11"
    fs2.Stream.emit(millVersion).through(fs2.text.utf8.encode).through(Files[IO].writeAll(root / ".mill-version")).compile.drain
  }

  private def createBuildMill(root: Path): IO[Unit] = {
    val buildContent = """import mill._
                         |import scalalib._
                         |
                         |object app extends ScalaModule {
                         |  def scalaVersion = "3.7.2-RC1-bin-20250616-61d9887-NIGHTLY"
                         |  def scalacOptions = Seq("-no-indent", "-Wunused:all")
                         |}
                         |""".stripMargin
    fs2.Stream.emit(buildContent).through(fs2.text.utf8.encode).through(Files[IO].writeAll(root / "build.mill")).compile.drain
  }

  private def createSourceFiles(root: Path): IO[Unit] = {
    val srcDir = root / "app" / "src"
    val mainContent = """object Main {
                         |  def main(args: Array[String]): Unit = {
                         |    println("Hello, World!")
                         |    val utils = new Utils()
                         |    utils.greet("Integration Test")
                         |  }
                         |}
                         |""".stripMargin

    val utilsContent = """class Utils {
                         |  def greet(name: String): Unit = {
                         |    println(s"Hello, $name!")
                         |  }
                         |
                         |  def add(a: Int, b: Int): Int = a + b
                         |
                         |  def multiply(x: Double, y: Double): Double = x * y
                         |}
                         |""".stripMargin

    Files[IO].createDirectories(srcDir) *>
      fs2.Stream.emit(mainContent).through(fs2.text.utf8.encode).through(Files[IO].writeAll(srcDir / "Main.scala")).compile.drain *>
      fs2.Stream.emit(utilsContent).through(fs2.text.utf8.encode).through(Files[IO].writeAll(srcDir / "Utils.scala")).compile.drain
  }

  private def createMultiModuleBuild(root: Path): IO[Unit] = {
    val buildContent = """import mill._
                         |import scalalib._
                         |
                         |trait CommonScalaModule extends ScalaModule {
                         |  def scalaVersion = "3.7.2-RC1-bin-20250616-61d9887-NIGHTLY"
                         |  def scalacOptions = Seq("-no-indent", "-Wunused:all")
                         |}
                         |
                         |object core extends CommonScalaModule
                         |
                         |object app extends CommonScalaModule {
                         |  def moduleDeps = Seq(core)
                         |}
                         |""".stripMargin
    fs2.Stream.emit(buildContent).through(fs2.text.utf8.encode).through(Files[IO].writeAll(root / "build.mill")).compile.drain
  }

  private def createMultiModuleSource(root: Path): IO[Unit] = {
    val coreDir = root / "core" / "src"
    val appDir = root / "app" / "src"

    val domainContent = """package core
                          |
                          |case class User(id: Long, name: String, email: String)
                          |
                          |object UserService {
                          |  def createUser(name: String, email: String): User = {
                          |    User(System.currentTimeMillis(), name, email)
                          |  }
                          |
                          |  def validateEmail(email: String): Boolean = {
                          |    email.contains("@") && email.contains(".")
                          |  }
                          |}
                          |""".stripMargin

    val mainContent = """package app
                        |
                        |import core.{User, UserService}
                        |
                        |object Main {
                        |  def main(args: Array[String]): Unit = {
                        |    val user = UserService.createUser("John Doe", "john@example.com")
                        |    println(s"Created user: $user")
                        |
                        |    val isValid = UserService.validateEmail(user.email)
                        |    println(s"Email is valid: $isValid")
                        |  }
                        |}
                        |""".stripMargin

    Files[IO].createDirectories(coreDir) *>
      Files[IO].createDirectories(appDir) *>
      fs2.Stream.emit(domainContent).through(fs2.text.utf8.encode).through(Files[IO].writeAll(coreDir / "Domain.scala")).compile.drain *>
      fs2.Stream.emit(mainContent).through(fs2.text.utf8.encode).through(Files[IO].writeAll(appDir / "Main.scala")).compile.drain
  }

  private def copyMillExecutable(root: Path): IO[Unit] = {
    // Find the project root by traversing upwards from the current directory
    def findProjectRoot(currentPath: Path): IO[Path] = {
      val millFile = currentPath / "mill"
      for {
        exists <- Files[IO].exists(millFile)
        isRegularFile <- if (exists) Files[IO].isRegularFile(millFile) else IO.pure(false)
        result <- if (exists && isRegularFile) {
          IO.pure(currentPath)
        } else {
          val parent = currentPath.parent
          parent match {
            case Some(p) => findProjectRoot(p)
            case None => IO.raiseError(new RuntimeException("Could not find mill executable in project hierarchy"))
          }
        }
      } yield result
    }
    
    for {
      currentDir <- IO.pure(Path.fromNioPath(java.nio.file.Paths.get(System.getProperty("user.dir"))))
      projectRoot <- findProjectRoot(currentDir)
      millSource = projectRoot / "mill"
      millTarget = root / "mill"
      _ <- Files[IO].copy(millSource, millTarget)
      // Make the mill file executable
      _ <- IO {
        import java.nio.file.Files as JFiles
        import java.nio.file.attribute.PosixFilePermission.*
        val perms = java.util.Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE)
        JFiles.setPosixFilePermissions(millTarget.toNioPath, perms)
      }
    } yield ()
  }
}
