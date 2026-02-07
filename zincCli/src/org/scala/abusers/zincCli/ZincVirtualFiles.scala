package org.scala.abusers.zincCli

import xsbti.VirtualFile
import java.io.InputStream
import java.nio.file.Path
import xsbti.VirtualFileRef
import xsbti.PathBasedFile
import java.nio.file.Paths
import java.{util => ju}

// we probably want to track which files are up to date and which are not here ? TODO
//
object ZincVirtualFiles {

  def toVirtualFileRef(p: Path): VirtualFileRef = toVirtualFileRef(p.toString)

  def toVirtualFileRef(pathStr: String): VirtualFileRef = {
    toVirtualFileImpl(pathStr).getOrElse {
      // anything else is probably a source file, and we need to need to use the BasicVirtualFileRef to avoid
      // problems in change detection (since BasicVirtualFileRef and VirtualSourceFile cooperate on equality)
      VirtualFileRef.of(pathStr)
    }
  }

  def toVirtualFile(p: Path): VirtualFile = toVirtualFile(p.toString)

  def toVirtualFile(pathStr: String): VirtualFile = {
    toVirtualFileImpl(pathStr).getOrElse {
      // Could be a java module (eg. //modules/java.base/java/lang/Object.class)
      SimpleVirtualFile(pathStr.intern())
    }
  }

  private def toVirtualFileImpl(pathStr: String): Option[VirtualFile] = {
    val idx = pathStr.indexOf('!')
    // intern the jar part of foo.jar!bar.class, and store it efficiently
    if (idx > 0) Some(ClassInJarVirtualFile(pathStr.substring(0, idx).intern(), pathStr.substring(idx + 1)))
    // intern foo.jar, and use our more efficient representation (since BasicVirtualFileRef weirdly holds three Strings)
    if (pathStr.endsWith(".jar")) Some(SimpleVirtualFile(pathStr.intern()))
    else None
  }


  trait VirtualJar extends PathBasedFile {
    def jarFile: Path
  }

  def substringAfterLast(str: String, ch: Char): String = {
    val idx = str.lastIndexOf(ch)
    if (idx < 0) str else str.substring(idx + 1)
  }

  /** Classes in jars - foo/bar/baz.jar!my/great/Class.class, etc. */
  final case class ClassInJarVirtualFile(jar: String, clazz: String)
      extends VirtualFileRef
      with VirtualJar {
    // it seems empirically that this is only called at the moment of writing protobufs, so it's a useful memory
    // optimization to avoid creating it until then
    override def id: String = s"$jar!$clazz"
    override def name: String = substringAfterLast(clazz, '/')
    override def names: Array[String] = jar.split("/") ++ clazz.split("/")

    override def jarFile: Path = Paths.get(jar)
    override def toPath: Path = Paths.get(id)
    override def contentHash: Long = ???
    override def input: InputStream = ???
  }
}
