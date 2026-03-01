package org.scala.abusers.zincCli

import sbt.internal.inc.*
import xsbti.VirtualFile
import java.nio.file.Path
import xsbti.VirtualFileRef
import xsbti.compile.analysis.ReadStamps
import java.{util => ju}
import java.io.File

class ZincCliStamper extends ReadStamps {
  private val emptyStamp = Stamp.getStamp(Map.empty, new File(""))
  FarmHash.fromLong(0)

  def isJdkPlatformPath(s: String): Boolean =
    (s.startsWith("//") && s.endsWith(".sig")) || s.startsWith("//modules/") || s.startsWith("/modules/")

  override def getAllProductStamps(): ju.Map[VirtualFileRef, xsbti.compile.analysis.Stamp] = ju.Map.of()
  override def getAllSourceStamps(): ju.Map[VirtualFileRef, xsbti.compile.analysis.Stamp] = ju.Map.of()
  override def getAllLibraryStamps(): ju.Map[VirtualFileRef, xsbti.compile.analysis.Stamp] = ju.Map.of()

  override def product(compilationProduct: VirtualFileRef): xsbti.compile.analysis.Stamp = emptyStamp

  override def library(libraryFile: VirtualFileRef): xsbti.compile.analysis.Stamp =
    if isJdkPlatformPath(libraryFile.id) then FarmHash.fromLong(0)
    else FarmHash.ofPath(Path.of(libraryFile.id))

  override def source(internalSource: VirtualFile): xsbti.compile.analysis.Stamp =
    internalSource match {
      case sourceFile: VirtualSourceFile => FarmHash.fromLong(sourceFile.contentHash())
      case virtualFile: VirtualFile       => FarmHash.ofFile(virtualFile)
    }
}
