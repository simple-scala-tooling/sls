package org.scala.abusers.sls.index

import cats.effect.IO
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri
import weaver.*

import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object IndexManagerSpec extends SimpleIOSuite {

  private val bytecodeIndexer = BytecodeIndexer()

  private def createJar(entries: List[(String, Array[Byte])]): IO[AbsolutePath] = IO.blocking {
    val tmp     = os.temp.dir(prefix = "index-manager-test")
    val jarPath = tmp / "test.jar"
    writeJar(jarPath.toIO, entries)
    AbsolutePath(jarPath.toNIO)
  }

  private def createMainAndSourcesJars(
      baseName: String,
      classEntries: List[(String, Array[Byte])],
      sourceEntries: List[(String, String)],
  ): IO[(AbsolutePath, AbsolutePath)] = IO.blocking {
    val tmp        = os.temp.dir(prefix = "index-manager-src-test")
    val mainJar    = tmp / s"$baseName.jar"
    val sourcesJar = tmp / s"$baseName-sources.jar"
    writeJar(mainJar.toIO, classEntries)
    writeJar(sourcesJar.toIO, sourceEntries.map { case (n, s) => n -> s.getBytes("UTF-8") })
    (AbsolutePath(mainJar.toNIO), AbsolutePath(sourcesJar.toNIO))
  }

  private def writeJar(file: java.io.File, entries: List[(String, Array[Byte])]): Unit = {
    val zos = new ZipOutputStream(new FileOutputStream(file))
    try
      entries.foreach { case (name, bytes) =>
        zos.putNextEntry(new ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
      }
    finally zos.close()
  }

  private def javaClass(name: String, access: Int = Opcodes.ACC_PUBLIC): (String, Array[Byte]) = {
    val cw = new ClassWriter(0)
    cw.visit(Opcodes.V17, access, name, null, "java/lang/Object", null)
    cw.visitEnd()
    (name + ".class", cw.toByteArray)
  }

  test("onFilesDeleted removes symbols from project index") {
    val uri = SourceUri("file:///test/Foo.scala")
    val sym = IndexedSymbol(
      id = SymbolId("test.Foo"),
      name = "Foo",
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri),
      parents = Nil,
      typeSignature = Some("test.Foo"),
    )

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      mgr = IndexManager(pi, di, bytecodeIndexer)
      _      <- pi.updateFiles(Map(uri -> (List(sym), Nil)))
      before <- pi.getSymbol(SymbolId("test.Foo"))
      _      <- mgr.onFilesDeleted(Set(uri))
      after  <- pi.getSymbol(SymbolId("test.Foo"))
    } yield expect(before.isDefined) and expect(after.isEmpty)
  }

  test("onFilesDeleted with empty set is a no-op") {
    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      mgr = IndexManager(pi, di, bytecodeIndexer)
      _ <- mgr.onFilesDeleted(Set.empty)
    } yield success
  }

  test("dependency JAR indexed via bytecode — symbols findable") {
    val cls = javaClass("com/example/Widget")
    for {
      jar <- createJar(List(cls))
      pi  <- ProjectIndex.empty
      di  <- DependencyIndex.empty
      mgr = IndexManager(pi, di, bytecodeIndexer)
      syms  <- bytecodeIndexer.indexJar(jar)
      _     <- di.addJar(jar.toNioPath.toString, syms)
      found <- di.searchSymbols("widget")
    } yield expect(found.exists(_.name == "Widget"))
  }

  test("corrupt JAR does not crash indexing") {
    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      mgr        = IndexManager(pi, di, bytecodeIndexer)
      tmp        = os.temp.dir(prefix = "corrupt-jar-test")
      corruptJar = tmp / "corrupt.jar"
      _ <- IO.blocking(os.write(corruptJar, "not a jar"))
      corruptJarAbs = AbsolutePath(corruptJar.toNIO)
      syms <- bytecodeIndexer.indexJar(corruptJarAbs).handleError(_ => Nil)
      _    <- di.addJar(corruptJarAbs.toNioPath.toString, syms)
    } yield success
  }

  test("searchSymbols with various query styles finds symbols") {
    val uri = SourceUri("file:///test/HashMap.scala")
    val sym = IndexedSymbol(
      id = SymbolId("scala.collection.HashMap"),
      name = "HashMap",
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri),
      parents = Nil,
      typeSignature = None,
    )

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      idx = SymbolIndex(pi, di)
      _        <- pi.updateFiles(Map(uri -> (List(sym), Nil)))
      byLower  <- idx.searchSymbols("hashmap") // lowercase prefix
      byCamel  <- idx.searchSymbols("HM")      // CamelCase abbreviation: H=Hash, M=Map
      byPrefix <- idx.searchSymbols("hash")    // lowercase prefix
      byPascal <- idx.searchSymbols("HashMap") // PascalCase → name prefix search
    } yield expect(byLower.exists(_.name == "HashMap")) and
      expect(byCamel.exists(_.name == "HashMap")) and
      expect(byPrefix.exists(_.name == "HashMap")) and
      expect(byPascal.exists(_.name == "HashMap"))
  }

  test("SymbolIndex.searchSymbols finds project and dependency symbols") {
    val uri     = SourceUri("file:///test/Foo.scala")
    val projSym = IndexedSymbol(
      id = SymbolId("test.Foo"),
      name = "Foo",
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri),
      parents = Nil,
      typeSignature = None,
    )
    val depCls = javaClass("com/example/FooBar")

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      idx = SymbolIndex(pi, di)
      _         <- pi.updateFiles(Map(uri -> (List(projSym), Nil)))
      jar       <- createJar(List(depCls))
      syms      <- bytecodeIndexer.indexJar(jar)
      _         <- di.addJar(jar.toString, syms)
      results   <- idx.searchSymbols("foo")
      projCount <- pi.symbolCount
      depCount  <- di.symbolCount
    } yield expect(results.exists(_.name == "Foo")) and
      expect(results.exists(_.name == "FooBar")) and
      expect(projCount == 1) and
      expect(depCount >= 1)
  }

  test("CamelCase query 'IO' does not match camelCase 'indexOf'") {
    val uri = SourceUri("file:///test/Stuff.scala")
    val sym = IndexedSymbol(
      id = SymbolId("test.indexOf"),
      name = "indexOf",
      kind = SymbolKind.Method,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri),
      parents = Nil,
      typeSignature = None,
    )

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      idx = SymbolIndex(pi, di)
      _       <- pi.updateFiles(Map(uri -> (List(sym), Nil)))
      results <- idx.searchSymbols("IO")
    } yield expect(!results.exists(_.name == "indexOf"))
  }

  test("mixed case 'iO' matches camelCase 'iOnly' via name prefix") {
    val uri = SourceUri("file:///test/Stuff.scala")
    val sym = IndexedSymbol(
      id = SymbolId("test.iOnly"),
      name = "iOnly",
      kind = SymbolKind.Method,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri),
      parents = Nil,
      typeSignature = None,
    )

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      idx = SymbolIndex(pi, di)
      _       <- pi.updateFiles(Map(uri -> (List(sym), Nil)))
      results <- idx.searchSymbols("iO")
    } yield expect(results.exists(_.name == "iOnly"))
  }

  test("lowercase query 'minutes' matches all-caps 'MINUTES'") {
    val uri = SourceUri("file:///test/Constants.scala")
    val sym = IndexedSymbol(
      id = SymbolId("java.util.concurrent.TimeUnit.MINUTES"),
      name = "MINUTES",
      kind = SymbolKind.Field,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri),
      parents = Nil,
      typeSignature = None,
    )

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      idx = SymbolIndex(pi, di)
      _       <- pi.updateFiles(Map(uri -> (List(sym), Nil)))
      results <- idx.searchSymbols("minutes")
    } yield expect(results.exists(_.name == "MINUTES"))
  }

  test("multiple files deleted — all symbols removed") {
    val uri1 = SourceUri("file:///test/A.scala")
    val uri2 = SourceUri("file:///test/B.scala")
    val sym1 = IndexedSymbol(
      id = SymbolId("test.A"),
      name = "A",
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri1, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri1),
      parents = Nil,
      typeSignature = None,
    )
    val sym2 = IndexedSymbol(
      id = SymbolId("test.B"),
      name = "B",
      kind = SymbolKind.Class,
      visibility = Visibility.Public,
      owner = None,
      location = Some(Location(uri2, 0, 0, 5, 1)),
      origin = SymbolOrigin.ProjectTasty("test", uri2),
      parents = Nil,
      typeSignature = None,
    )

    for {
      pi <- ProjectIndex.empty
      di <- DependencyIndex.empty
      mgr = IndexManager(pi, di, bytecodeIndexer)
      _ <- pi.updateFiles(
        Map(
          uri1 -> (List(sym1), Nil),
          uri2 -> (List(sym2), Nil),
        )
      )
      _ <- mgr.onFilesDeleted(Set(uri1, uri2))
      a <- pi.getSymbol(SymbolId("test.A"))
      b <- pi.getSymbol(SymbolId("test.B"))
    } yield expect(a.isEmpty) and expect(b.isEmpty)
  }

  test("dep jar with -sources.jar containing Java is indexed via JavaIndexer with DependencySource origin") {
    val javaSource =
      """package com.example;
        |public class Widget {
        |    public String label;
        |    public int compute(int x) { return x; }
        |}
        |""".stripMargin

    val mainCls = javaClass("com/example/Widget")
    for {
      jars <- createMainAndSourcesJars(
        baseName = "widget-1.0.0",
        classEntries = List(mainCls),
        sourceEntries = List("com/example/Widget.java" -> javaSource),
      )
      (mainJar, _) = jars
      pi    <- ProjectIndex.empty
      di    <- DependencyIndex.empty
      mgr = IndexManager(pi, di, bytecodeIndexer)
      _     <- mgr.indexJarSafely(mainJar, Nil)
      found <- di.getSymbolsByName("compute")
    } yield {
      val widgetCompute = found.find(_.owner.exists(_.value == "com.example.Widget"))
      expect(widgetCompute.isDefined) and
        expect(widgetCompute.exists(_.origin match {
          case SymbolOrigin.DependencySource(jp, _) => jp == mainJar.toNioPath.toString
          case _                                    => false
        }))
    }
  }

  test("dep jar with no -sources.jar falls back to bytecode indexing") {
    val mainCls = javaClass("com/example/NoSrc")
    for {
      jar <- createJar(List(mainCls))
      pi  <- ProjectIndex.empty
      di  <- DependencyIndex.empty
      mgr = IndexManager(pi, di, bytecodeIndexer)
      _     <- mgr.indexJarSafely(jar, Nil)
      found <- di.getSymbolsByName("NoSrc")
    } yield expect(found.exists(_.origin.isInstanceOf[SymbolOrigin.DependencyClassfile]))
  }

}
