package org.scala.abusers.sls.index

import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.scala.abusers.sls.index.IndexedSymbolCodecs.given
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import scala.util.Try
import scala.util.Using

/** On-disk cache for the symbols produced by indexing a dependency's source jar. Keyed by the source jar's SHA-256 and
  * the cache schema version, so entries stay valid across projects and across changes to the project classpath — that
  * hermeticity is the whole point: when the typing classpath is the lib's own coursier-resolved deps (not the project
  * CP), the indexer output depends only on the jar bytes plus our extraction logic.
  *
  * Bump [[DepIndexCache.Version]] whenever the cached JSON representation changes — adds/removes/renames in
  * [[IndexedSymbol]] / [[SymbolOrigin]] / etc., or anything that would make old entries decode to surprising values.
  * Each version lives in its own subdirectory so old entries are simply orphaned, never read.
  */
class DepIndexCache(root: Path) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val dir    = root.resolve(s"v${DepIndexCache.Version}")

  def cachePath(sha: String): Path = dir.resolve(s"$sha.json")

  def lookup(sha: String): IO[Option[List[IndexedSymbol]]] = IO.blocking {
    val p = cachePath(sha)
    if !Files.exists(p) then None
    else
      Try {
        Using.resource(Files.newInputStream(p))(readFromStream[List[IndexedSymbol]](_))
      }.recover { case t =>
        logger.warn(s"Dep index cache entry $p unreadable, treating as miss", t)
        Files.deleteIfExists(p)
        throw t
      }.toOption
  }

  def store(sha: String, symbols: List[IndexedSymbol]): IO[Unit] = IO.blocking {
    Files.createDirectories(dir)
    val tmp = Files.createTempFile(dir, "dep-idx-", ".tmp")
    try {
      Using.resource(Files.newOutputStream(tmp))(writeToStream(symbols, _))
      Files.move(tmp, cachePath(sha), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case t: Throwable =>
        Files.deleteIfExists(tmp)
        logger.warn(s"Failed to write dep index cache for sha=$sha", t)
    }
  }

  def hashJar(path: Path): IO[String] = IO.blocking {
    val digest = MessageDigest.getInstance("SHA-256")
    Using.resource(Files.newInputStream(path)) { is =>
      val buf = new Array[Byte](16 * 1024)
      var n   = is.read(buf)
      while n > 0 do {
        digest.update(buf, 0, n)
        n = is.read(buf)
      }
    }
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }
}

object DepIndexCache {

  /** Bump on any breaking change to the cached IndexedSymbol JSON shape or to the indexer's extraction logic. */
  val Version: Int = 2

  def default: DepIndexCache =
    new DepIndexCache(XdgCacheDir.cacheHome.resolve("sls/dep-index"))
}
