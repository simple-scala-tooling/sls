package org.scala.abusers.sls.index

import java.nio.file.Path

/** Resolves the XDG base directory for caches. Honours `$XDG_CACHE_HOME` when set and non-empty, otherwise falls back
  * to `$HOME/.cache` per the XDG Base Directory spec.
  */
object XdgCacheDir {
  def cacheHome: Path =
    sys.env
      .get("XDG_CACHE_HOME")
      .filter(_.nonEmpty)
      .map(Path.of(_))
      .getOrElse(Path.of(sys.props("user.home"), ".cache"))
}
