package org.scala.abusers.zincCli

import xsbti.VirtualFile
import net.openhft.hashing.LongHashFunction
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.io.ByteArrayInputStream
import xsbti.PathBasedFile
import java.{util => ju}
import xsbti.BasicVirtualFileRef

/**
 * In-memory source file. Not a case class because we don't want to use the raw Path for equality (BasicVirtualFileRef
 * implements id-based equality for us).
 */
class VirtualSourceFile private (p: Path, c: HashedContent) extends BasicVirtualFileRef(p.toAbsolutePath().toString) with VirtualFile {
  override def contentHash(): Long = LongHashFunction.farmNa().hashChars(c.hash)
  override def input(): InputStream = c.contentAsInputStream

  override def toString: String = s"VirtualSourceFile($id@${c.hash})"
}

object VirtualSourceFile {
  def apply(p: Path, c: HashedContent): VirtualSourceFile = new VirtualSourceFile(p, c)
}


trait HashedContent {
  def hash: String
  def contentAsInputStream: InputStream
  def utf8ContentAsString: String
  def size: Int
  def compressedSize: Int
  // def tpe: ContentType // TODO
}

object HashedContent {
  val hashFunction = com.google.common.hash.Hashing.sha256()

  def of(path: Path): HashedContent = {
    new HashedContent {
      def contentAsInputStream = new ByteArrayInputStream(utf8ContentAsString.getBytes())
      val utf8ContentAsString = Files.readString(path)
      def size = utf8ContentAsString.size
      def compressedSize: Int = size
      def hash: String = hashFunction.hashString(utf8ContentAsString, StandardCharsets.UTF_8).toString
    }
  }
}

// final case class ClassInJarVirtualFile private (jar: String, clazz: String)
//     extends VirtualFileRef
//     with VirtualJar {
//   // it seems empirically that this is only called at the moment of writing protobufs, so it's a useful memory
//   // optimization to avoid creating it until then
//   override def id: String = s"$jar!$clazz"
//   override def name: String = ZincUtils.substringAfterLast(clazz, '/')
//   override def names: Array[String] = jar.split("/") ++ clazz.split("/")

//   override def jarFile: JarAsset = JarAsset.resolve(Paths.get(jar))
//   override def toPath: Path = Paths.get(id)
//   override def contentHash: Long = ???
//   override def input: InputStream = ???
// }

final case class SimpleVirtualFile(override val id: String)
    extends BasicVirtualFileRef(id) with VirtualFile with PathBasedFile {

  override def input(): InputStream = Files.newInputStream(toPath)

  override def contentHash(): Long = ???

  override lazy val toPath: Path = Path.of(id)
}

object SimpleVirtualFile {
  def apply(p: Path): SimpleVirtualFile = SimpleVirtualFile(p.toAbsolutePath.toString.intern())
}
