package org.scala.abusers.sls.index

import org.scala.abusers.sls.AbsolutePath

import java.nio.file.Path

/** Locates the JDK source archive. JDK 9+ ships sources for all modules in `$JAVA_HOME/lib/src.zip`. Older JDKs (and
  * pure JRE installations) don't ship sources at all, in which case `find` returns `None`.
  */
object JdkSources {
  def find(): Option[AbsolutePath] =
    Option(sys.props("java.home"))
      .map(home => AbsolutePath(Path.of(home, "lib", "src.zip")))
      .filter(_.exists)
}
