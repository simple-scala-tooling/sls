package org.scala.abusers.zincCli

import xsbti.VirtualFile
import java.nio.file.Path
import xsbti.FileConverter
import xsbti.VirtualFileRef
import xsbti.PathBasedFile
import java.nio.file.Paths
import java.{util => ju}

class ZincFileConverter(
      virtualSources: Map[VirtualFileRef, VirtualFile], // in practice it's Map[VirtualSourceFile, VirtualSourceFile]
      virtualJars: Map[Path, VirtualFile] // in practice it's Map[Path, SimpleVirtualFile]
  ) extends FileConverter {
    override def toPath(ref: VirtualFileRef): Path = ref match {
      case pbf: PathBasedFile => pbf.toPath
      case _                  => Paths.get(ref.id)
    }
    override def toVirtualFile(ref: VirtualFileRef): VirtualFile = ref match {
      case v: VirtualFile => v
      case r              => virtualSources.getOrElse(r, ZincVirtualFiles.toVirtualFile(ref.id))
    }
    override def toVirtualFile(path: Path): VirtualFile =
      virtualJars.getOrElse(path, ZincVirtualFiles.toVirtualFile(path))
  }