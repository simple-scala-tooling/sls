package org.scala.abusers.sls.index

import cats.effect.IO
import fs2.hashing.HashAlgorithm
import fs2.hashing.Hashing
import fs2.io.file.CopyFlag
import fs2.io.file.CopyFlags
import fs2.io.file.Files
import fs2.io.file.Path as Fs2Path
import fs2.Stream
import org.slf4j.LoggerFactory

import java.nio.file.Path

/** On-disk cache for the symbols produced by indexing a dependency's source jar. Keyed by the source jar's SHA-256 and
  * the cache schema version, so entries stay valid across projects and across changes to the project classpath — that
  * hermeticity is the whole point: when the typing classpath is the lib's own coursier-resolved deps (not the project
  * CP), the indexer output depends only on the jar bytes plus our extraction logic.
  *
  * The wire format is smithy4s-protobuf over the [[org.scala.abusers.sls.depindex.CachedDepIndex]] schema, with all
  * strings interned into a per-file table so the repetitive FQNs, jar paths, and source URIs collapse to varint
  * indices. Bump [[DepIndexCache.Version]] on any change to that schema or the indexer's extraction logic. Each version
  * lives in its own subdirectory so old entries are simply orphaned, never read.
  */
class DepIndexCache(root: Path) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val dir    = root.resolve(s"v${DepIndexCache.Version}")

  def cachePath(sha: String): Path = dir.resolve(s"$sha.bin")

  def lookup(sha: String): IO[Option[List[IndexedSymbol]]] = {
    val p = Fs2Path.fromNioPath(cachePath(sha))
    Files[IO].exists(p).flatMap {
      case false => IO.pure(None)
      case true  =>
        Files[IO]
          .readAll(p)
          .compile
          .to(Array)
          .flatMap { bytes =>
            IndexedSymbolProtoCodec.decode(bytes) match {
              case Right(symbols) => IO.pure(Some(symbols))
              case Left(err)      =>
                IO(logger.warn(s"Dep index cache entry $p unreadable, treating as miss: $err")) *>
                  Files[IO].deleteIfExists(p).attempt.as(None)
            }
          }
          .handleErrorWith { t =>
            IO(logger.warn(s"Dep index cache entry $p unreadable, treating as miss", t)) *>
              Files[IO].deleteIfExists(p).attempt.as(None)
          }
    }
  }

  def store(sha: String, symbols: List[IndexedSymbol]): IO[Unit] = {
    val target = Fs2Path.fromNioPath(cachePath(sha))
    val dirFs2 = Fs2Path.fromNioPath(dir)
    val bytes  = IndexedSymbolProtoCodec.encode(symbols)
    val flags  = CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting)
    Files[IO].createDirectories(dirFs2) *>
      Files[IO].createTempFile(Some(dirFs2), "dep-idx-", ".tmp", None).flatMap { tmp =>
        (Stream
          .emits(bytes)
          .through(Files[IO].writeAll(tmp))
          .compile
          .drain *> Files[IO].move(tmp, target, flags)).handleErrorWith { t =>
          Files[IO].deleteIfExists(tmp).attempt *>
            IO(logger.warn(s"Failed to write dep index cache for sha=$sha", t))
        }
      }
  }

  def hashJar(path: Path): IO[String] =
    Files[IO]
      .readAll(Fs2Path.fromNioPath(path))
      .through(Hashing[IO].hash(HashAlgorithm.SHA256))
      .compile
      .lastOrError
      .map(_.bytes.toArray.map(b => f"${b & 0xff}%02x").mkString)
}

object DepIndexCache {

  /** Bump on any breaking change to the cached IndexedSymbol shape or to the indexer's extraction logic. */
  val Version: Int = 3

  def default: DepIndexCache =
    new DepIndexCache(XdgCacheDir.cacheHome.resolve("sls/dep-index"))
}
