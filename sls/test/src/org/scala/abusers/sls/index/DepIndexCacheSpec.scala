package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.SourceUri
import weaver.*

import java.nio.file.Files

object DepIndexCacheSpec extends SimpleIOSuite {

  private def sample(name: String): IndexedSymbol = IndexedSymbol(
    id = SymbolId(s"com.example.$name"),
    name = name,
    kind = SymbolKind.Class,
    visibility = Visibility.Public,
    owner = None,
    location = Some(Location(SourceUri("jar:file:///foo.jar!/Foo.java"), 1, 0, 10, 1)),
    origin = SymbolOrigin.DependencySource("/path/to/foo-1.0.0.jar", SourceUri("jar:file:///foo.jar!/Foo.java")),
    parents = List(SymbolId("java.lang.Object")),
    typeSignature = Some(s"com.example.$name"),
  )

  private def freshCache: IO[(DepIndexCache, java.nio.file.Path)] = IO.blocking {
    val tmp = Files.createTempDirectory("dep-index-cache-test")
    (new DepIndexCache(tmp), tmp)
  }

  test("lookup returns None for missing entry") {
    for {
      (cache, _) <- freshCache
      result     <- cache.lookup("nonexistent")
    } yield expect(result.isEmpty)
  }

  test("store then lookup roundtrips a symbol list") {
    val syms = List(sample("Foo"), sample("Bar"))
    for {
      (cache, _) <- freshCache
      _          <- cache.store("abc123", syms)
      result     <- cache.lookup("abc123")
    } yield expect(result.contains(syms))
  }

  test("hashJar produces deterministic SHA-256 hex for identical bytes") {
    for {
      (cache, dir) <- freshCache
      a = dir.resolve("a.bin")
      b = dir.resolve("b.bin")
      _  <- IO.blocking(Files.write(a, "hello world".getBytes("UTF-8")))
      _  <- IO.blocking(Files.write(b, "hello world".getBytes("UTF-8")))
      ha <- cache.hashJar(a)
      hb <- cache.hashJar(b)
    } yield expect(ha == hb) and
      expect(ha == "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9")
  }

  test("hashJar differs for different bytes") {
    for {
      (cache, dir) <- freshCache
      a = dir.resolve("a.bin")
      b = dir.resolve("b.bin")
      _  <- IO.blocking(Files.write(a, "hello".getBytes("UTF-8")))
      _  <- IO.blocking(Files.write(b, "world".getBytes("UTF-8")))
      ha <- cache.hashJar(a)
      hb <- cache.hashJar(b)
    } yield expect(ha != hb)
  }
}
