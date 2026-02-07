package org.scala.abusers.zincCli

import sbt.internal.inc.*
import java.nio.file.Files
import java.{util => ju}
import xsbti.compile.AnalysisStore
import xsbti.compile.AnalysisContents
import xsbti.compile.analysis.ReadWriteMappers

class ZincCliAnalysisStore(analysisJar: OutputJar) extends AnalysisStore {
  if !Files.exists(analysisJar.temp.getParent) then Files.createDirectories(analysisJar.temp.getParent)
  val underlying = FileAnalysisStore.binary(
    analysisJar.path.toFile,
    ReadWriteMappers.getEmptyMappers(),
    tmpDir = analysisJar.temp.getParent().toFile
  )

  override def set(analysisContents: AnalysisContents): Unit =  underlying.set(analysisContents)

  override def unsafeGet(): AnalysisContents = underlying.unsafeGet()

  override def get(): ju.Optional[AnalysisContents] = underlying.get()
}
