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

  override def get(): ju.Optional[AnalysisContents] = underlying.get()
}
