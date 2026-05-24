package org.scala.abusers.sls.index

import cats.effect.IO
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.scala.abusers.sls.AbsolutePath

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import scala.collection.mutable.ListBuffer

class BytecodeIndexer {

  def indexJar(jarPath: AbsolutePath): IO[List[IndexedSymbol]] =
    IO.blocking {
      val symbols = ListBuffer.empty[IndexedSymbol]
      val origin  = SymbolOrigin.DependencyClassfile(jarPath.toNioPath.toString)
      val zis     = new ZipInputStream(new FileInputStream(jarPath.toFile))
      try {
        var entry: ZipEntry = zis.getNextEntry
        while (entry != null) {
          if (entry.getName.endsWith(".class") && !entry.isDirectory) {
            try {
              val bytes   = readEntry(zis)
              val reader  = new ClassReader(bytes)
              val visitor = new IndexClassVisitor(origin)
              reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
              symbols ++= visitor.symbols
            } catch { case _: Exception => () }
          }
          zis.closeEntry()
          entry = zis.getNextEntry
        }
      } finally zis.close()
      symbols.toList
    }

  private def readEntry(zis: ZipInputStream): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val buf  = new Array[Byte](4096)
    var n    = zis.read(buf)
    while (n != -1) {
      baos.write(buf, 0, n)
      n = zis.read(buf)
    }
    baos.toByteArray
  }
}

private class IndexClassVisitor(origin: SymbolOrigin) extends ClassVisitor(Opcodes.ASM9) {
  val symbols: ListBuffer[IndexedSymbol] = ListBuffer.empty

  /** JVM internal name (e.g. `crossproducer/Lib$Inner` or `crossproducer/Lib$`). Passed through `SymbolId.fromJvm` to
    * produce canonical ids; kept verbatim so methods/fields can be built off the same internal name.
    */
  private var internalName: String    = ""
  private var classSymbolId: SymbolId = SymbolId.tpe(Nil, Nil, "")
  override def visit(
      version: Int,
      access: Int,
      name: String,
      signature: String,
      superName: String,
      interfaces: Array[String],
  ): Unit = {
    internalName = name
    classSymbolId = SymbolId.fromJvm(name, memberName = None)

    if (shouldSkipClass(name, access)) return

    val kind    = classKind(name, access)
    val vis     = accessToVisibility(access)
    val parents = buildParents(superName, interfaces)

    symbols += IndexedSymbol(
      id = classSymbolId,
      name = classSymbolId.name,
      kind = kind,
      visibility = vis,
      owner = ownerFromInternalName(name),
      location = None,
      origin = origin,
      parents = parents,
      typeSignature = Some(classSymbolId.render),
    )
  }

  override def visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String,
      exceptions: Array[String],
  ): MethodVisitor = {
    if (shouldSkipMethod(name, access)) return null

    val kind     = if (name == "<init>") SymbolKind.Constructor else SymbolKind.Method
    val vis      = accessToVisibility(access)
    val methodId = SymbolId.fromJvm(internalName, memberName = Some(name))
    val sig      = Option(signature).getOrElse(descriptor)

    symbols += IndexedSymbol(
      id = methodId,
      name = name,
      kind = kind,
      visibility = vis,
      owner = Some(classSymbolId),
      location = None,
      origin = origin,
      parents = Nil,
      typeSignature = Some(sig),
    )
    null
  }

  override def visitField(
      access: Int,
      name: String,
      descriptor: String,
      signature: String,
      value: AnyRef,
  ): FieldVisitor = {
    if (isSynthetic(access)) return null

    val vis  = accessToVisibility(access)
    val kind =
      if ((access & Opcodes.ACC_ENUM) != 0) SymbolKind.EnumCase
      else if ((access & Opcodes.ACC_FINAL) != 0) SymbolKind.Val
      else SymbolKind.Var
    val fieldId = SymbolId.fromJvm(internalName, memberName = Some(name))
    val sig     = Option(signature).getOrElse(descriptor)

    symbols += IndexedSymbol(
      id = fieldId,
      name = name,
      kind = kind,
      visibility = vis,
      owner = Some(classSymbolId),
      location = None,
      origin = origin,
      parents = Nil,
      typeSignature = Some(sig),
    )
    null
  }

  private def classKind(name: String, access: Int): SymbolKind = {
    val simpleName = name.split('/').last
    if ((access & Opcodes.ACC_ENUM) != 0) SymbolKind.Enum
    else if ((access & Opcodes.ACC_INTERFACE) != 0) SymbolKind.Trait
    else if (simpleName.endsWith("$") && !simpleName.endsWith("$$")) SymbolKind.Object
    else SymbolKind.Class
  }

  private def accessToVisibility(access: Int): Visibility =
    if ((access & Opcodes.ACC_PRIVATE) != 0) Visibility.Private
    else if ((access & Opcodes.ACC_PROTECTED) != 0) Visibility.Protected
    else Visibility.Public

  private def shouldSkipClass(name: String, access: Int): Boolean = {
    val simpleName = name.split('/').last
    isSynthetic(access) ||
    simpleName.contains("$anon") ||
    simpleName == "package" ||
    simpleName.startsWith("$")
  }

  private def shouldSkipMethod(name: String, access: Int): Boolean =
    isSynthetic(access) ||
      isBridge(access) ||
      name == "<clinit>" ||
      name.contains("$default$") ||
      name.startsWith("$")

  private def isSynthetic(access: Int): Boolean =
    (access & Opcodes.ACC_SYNTHETIC) != 0

  private def isBridge(access: Int): Boolean =
    (access & Opcodes.ACC_BRIDGE) != 0

  private def buildParents(superName: String, interfaces: Array[String]): List[SymbolId] = {
    val parents = ListBuffer.empty[SymbolId]
    if (superName != null && superName != "java/lang/Object")
      parents += SymbolId.fromJvm(superName, memberName = None)
    if (interfaces != null)
      interfaces.foreach(i => parents += SymbolId.fromJvm(i, memberName = None))
    parents.toList
  }

  /** Owner of a class is its lexically-enclosing class for inner classes, otherwise None. We deliberately do *not*
    * encode the package as the owner — packages are not types in our canonical model.
    */
  private def ownerFromInternalName(name: String): Option[SymbolId] = {
    val lastSlash = name.lastIndexOf('/')
    // Trailing `$` on a class name means the module class itself; strip it before looking for an outer.
    val core      = if (name.endsWith("$")) name.dropRight(1) else name
    val classPart =
      if (lastSlash >= 0) core.substring(lastSlash + 1) else core
    val lastDollar = classPart.lastIndexOf('$')
    if (lastDollar > 0) {
      val pkgPart   = if (lastSlash >= 0) name.substring(0, lastSlash + 1) else ""
      val ownerName = pkgPart + classPart.substring(0, lastDollar)
      Some(SymbolId.fromJvm(ownerName, memberName = None))
    } else None
  }
}

object BytecodeIndexer {
  def apply(): BytecodeIndexer = new BytecodeIndexer()
}
