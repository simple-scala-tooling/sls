package org.scala.abusers.sls.index

import cats.effect.IO
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.scala.abusers.sls.AbsolutePath
import weaver.*

import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BytecodeIndexerSpec extends SimpleIOSuite {

  private val indexer = BytecodeIndexer()

  private def createJar(entries: List[(String, Array[Byte])]): IO[AbsolutePath] = IO.blocking {
    val tmp     = os.temp.dir(prefix = "bytecode-indexer-test")
    val jarPath = tmp / "test.jar"
    val zos     = new ZipOutputStream(new FileOutputStream(jarPath.toIO))
    try
      entries.foreach { case (name, bytes) =>
        zos.putNextEntry(new ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
      }
    finally zos.close()
    AbsolutePath(jarPath.toNIO)
  }

  private def javaClass(
      name: String,
      access: Int = Opcodes.ACC_PUBLIC,
      superName: String = "java/lang/Object",
      interfaces: Array[String] = null,
      methods: List[(String, String, Int)] = Nil,
      fields: List[(String, String, Int)] = Nil,
  ): (String, Array[Byte]) = {
    val cw = new ClassWriter(0)
    cw.visit(Opcodes.V17, access, name, null, superName, interfaces)
    cw.visitSource("Test.java", null)
    methods.foreach { case (mName, desc, mAccess) =>
      val mv = cw.visitMethod(mAccess, mName, desc, null, null)
      mv.visitCode()
      mv.visitInsn(Opcodes.RETURN)
      mv.visitMaxs(1, 1)
      mv.visitEnd()
    }
    fields.foreach { case (fName, desc, fAccess) =>
      cw.visitField(fAccess, fName, desc, null, null).visitEnd()
    }
    cw.visitEnd()
    (name + ".class", cw.toByteArray)
  }

  test("Java class — class and method symbols extracted") {
    val cls = javaClass(
      "com/example/Greeter",
      methods = List(("greet", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.ACC_PUBLIC)),
    )
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      classSym  = syms.find(s => s.name == "Greeter" && s.kind == SymbolKind.Class)
      methodSym = syms.find(s => s.name == "greet" && s.kind == SymbolKind.Method)
    } yield expect(classSym.isDefined) and expect(methodSym.isDefined)
  }

  test("interface — Trait kind") {
    val cls =
      javaClass("com/example/Runnable", access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      traitSym = syms.find(s => s.name == "Runnable" && s.kind == SymbolKind.Trait)
    } yield expect(traitSym.isDefined)
  }

  test("enum — Enum kind") {
    val cls = javaClass(
      "com/example/Color",
      access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM | Opcodes.ACC_FINAL,
      superName = "java/lang/Enum",
      fields = List(
        ("RED", "Lcom/example/Color;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM)
      ),
    )
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      enumSym = syms.find(s => s.name == "Color" && s.kind == SymbolKind.Enum)
      caseSym = syms.find(s => s.name == "RED" && s.kind == SymbolKind.EnumCase)
    } yield expect(enumSym.isDefined) and expect(caseSym.isDefined)
  }

  test("access flags — correct Visibility") {
    val cls = javaClass(
      "com/example/Vis",
      methods = List(
        ("publicMethod", "()V", Opcodes.ACC_PUBLIC),
        ("privateMethod", "()V", Opcodes.ACC_PRIVATE),
        ("protectedMethod", "()V", Opcodes.ACC_PROTECTED),
      ),
    )
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      pub  = syms.find(_.name == "publicMethod")
      priv = syms.find(_.name == "privateMethod")
      prot = syms.find(_.name == "protectedMethod")
    } yield expect(pub.exists(_.visibility == Visibility.Public)) and
      expect(priv.exists(_.visibility == Visibility.Private)) and
      expect(prot.exists(_.visibility == Visibility.Protected))
  }

  test("synthetic methods filtered") {
    val cls = javaClass(
      "com/example/Synth",
      methods = List(
        ("real", "()V", Opcodes.ACC_PUBLIC),
        ("synthetic", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC),
        ("bridge", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE),
        ("<clinit>", "()V", Opcodes.ACC_STATIC),
      ),
    )
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      methodNames = syms.filter(_.kind == SymbolKind.Method).map(_.name).toSet
    } yield expect(methodNames.contains("real")) and
      expect(!methodNames.contains("synthetic")) and
      expect(!methodNames.contains("bridge")) and
      expect(!methodNames.contains("<clinit>"))
  }

  test("Scala object companion detected via $ suffix") {
    val cls = javaClass("com/example/Foo$")
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      objSym = syms.find(s => s.name == "Foo" && s.kind == SymbolKind.Object)
    } yield expect(objSym.isDefined)
  }

  test("inner classes attributed to owners") {
    val outer = javaClass("com/example/Outer")
    val inner = javaClass("com/example/Outer$Inner")
    for {
      jar  <- createJar(List(outer, inner))
      syms <- indexer.indexJar(jar)
      innerSym = syms.find(_.name == "Inner")
    } yield expect(innerSym.exists(_.owner.contains(IndexTestFixtures.tid("com.example.Outer"))))
  }

  test("parent classes extracted") {
    val base  = javaClass("com/example/Base")
    val child =
      javaClass("com/example/Child", superName = "com/example/Base", interfaces = Array("java/io/Serializable"))
    for {
      jar  <- createJar(List(base, child))
      syms <- indexer.indexJar(jar)
      childSym = syms.find(_.id == IndexTestFixtures.tid("com.example.Child"))
      parents  = childSym.toList.flatMap(_.parents)
    } yield expect(parents.contains(IndexTestFixtures.tid("com.example.Base"))) and
      expect(parents.contains(IndexTestFixtures.tid("java.io.Serializable")))
  }

  test("all symbols have location = None and DependencyClassfile origin") {
    val cls = javaClass("com/example/Check")
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
    } yield expect(syms.forall(_.location.isEmpty)) and
      expect(syms.forall(_.origin.isInstanceOf[SymbolOrigin.DependencyClassfile]))
  }

  test("empty JAR — empty results") {
    for {
      jar  <- createJar(Nil)
      syms <- indexer.indexJar(jar)
    } yield expect(syms.isEmpty)
  }

  test("JAR with non-class resources — no crash") {
    val entries = List(
      "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0\n".getBytes,
      "readme.txt"           -> "hello".getBytes,
    )
    for {
      jar  <- createJar(entries)
      syms <- indexer.indexJar(jar)
    } yield expect(syms.isEmpty)
  }

  test("fields extracted") {
    val cls = javaClass(
      "com/example/WithFields",
      fields = List(
        ("count", "I", Opcodes.ACC_PUBLIC),
        ("name", "Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL),
      ),
    )
    for {
      jar  <- createJar(List(cls))
      syms <- indexer.indexJar(jar)
      countSym = syms.find(s => s.name == "count" && s.kind == SymbolKind.Var)
      nameSym  = syms.find(s => s.name == "name" && s.kind == SymbolKind.Val)
    } yield expect(countSym.isDefined) and expect(nameSym.isDefined)
  }
}
