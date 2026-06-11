package org.scala.abusers.sls.index
package util

import cats.effect.IO
import cats.effect.Resource
import dotty.tools.dotc.util.ClasspathFromClassloader
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri

import java.io.File.pathSeparator
import java.nio.file.Paths

/** A temp workspace whose sources were compiled with in-process dotc, ready to be indexed.
  *
  * Layer 2 of the test architecture (see `ai/test-infrastructure.md`): real TASTy from a real compilation, no frozen
  * fixture JARs, no BSP/zinc machinery. Build via [[TestWorkspace.withSources]].
  */
final case class TestWorkspace(
    sourceRoot: os.Path,
    classesRoot: os.Path,
    sources: Map[String, os.Path],
    classpath: List[AbsolutePath],
) {

  def uri(name: String): SourceUri =
    sources.getOrElse(name, sys.error(s"No source named '$name' in this workspace")).toNIO.toSourceUri

  def indexResults: IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    TastyIndexer("test-workspace").indexDirectory(AbsolutePath(classesRoot.toNIO), classpath)

  /** A fresh two-tier index populated from this workspace's TASTy output. Each call builds a new one, so tests can
    * compare independently-built indexes (e.g. the determinism property).
    */
  def symbolIndex: IO[SymbolIndex] =
    for {
      project    <- ProjectIndex.empty
      dependency <- DependencyIndex.empty
      results    <- indexResults
      _          <- project.updateFiles(results)
    } yield SymbolIndex(project, dependency)
}

object TestWorkspace {

  /** Compile `name -> source` pairs into a temp workspace via in-process `dotty.tools.dotc.Main`, against the test
    * runtime classpath. Acquisition fails if compilation fails (diagnostics go to the console). The temp directory
    * is removed on release.
    */
  def withSources(srcs: (String, String)*): Resource[IO, TestWorkspace] =
    Resource.make(IO.blocking {
      val root       = os.temp.dir(prefix = "sls-test-ws-")
      val sourceRoot = root / "src"
      val classesDir = root / "classes"
      os.makeDir.all(sourceRoot)
      os.makeDir.all(classesDir)

      val files = srcs.map { case (name, content) =>
        val path = sourceRoot / os.SubPath(name)
        os.write(path, content, createFolders = true)
        name -> path
      }.toMap

      val cpString = ClasspathFromClassloader(getClass.getClassLoader)
      val args     = Array("-d", classesDir.toString, "-classpath", cpString) ++ files.values.map(_.toString)
      val reporter = dotty.tools.dotc.Main.process(args)
      if reporter.hasErrors then
        throw new RuntimeException(
          s"TestWorkspace compilation failed with ${reporter.errorCount} error(s) — see console output"
        )

      val classpath = cpString.split(pathSeparator).toList.filter(_.nonEmpty).map(p => AbsolutePath(Paths.get(p)))
      TestWorkspace(sourceRoot, classesDir, files, classpath)
    })(ws => IO.blocking(os.remove.all(ws.sourceRoot / os.up)))
}
