package org.scala.abusers.zincCli

import sbt.internal.inc.*
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.AnalysisContents
import xsbti.compile.AnalysisStore

import java.{util => ju}
import java.nio.file.Files

class ZincCliAnalysisStore(analysisJar: OutputJar) extends AnalysisStore {
  if !Files.exists(analysisJar.temp.getParent) then Files.createDirectories(analysisJar.temp.getParent)
  val underlying = FileAnalysisStore.binary(
    analysisJar.path.toFile,
    ReadWriteMappers.getEmptyMappers(),
    tmpDir = analysisJar.temp.getParent().toFile,
  )

  override def set(analysisContents: AnalysisContents): Unit = underlying.set(analysisContents)

  override def unsafeGet(): AnalysisContents = underlying.unsafeGet()

  /** An unreadable store (torn write, garbage, version drift) must degrade to a clean compile, never fail the
    * server — zinc's binary store only swallows file-not-found and throws on corrupt bytes.
    */
  override def get(): ju.Optional[AnalysisContents] =
    try underlying.get()
    catch {
      case scala.util.control.NonFatal(e) =>
        System.err.println(s"Unreadable analysis store ${analysisJar.path}, falling back to clean compile: $e")
        ju.Optional.empty()
    }
}
