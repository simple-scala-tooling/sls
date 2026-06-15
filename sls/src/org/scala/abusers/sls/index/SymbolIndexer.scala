package org.scala.abusers.sls.index

import cats.effect.IO
import org.scala.abusers.sls.AbsolutePath
import org.typelevel.otel4s.trace.Tracer

/** Producer-agnostic view of a JAR-indexer: hand it a JAR and a classpath, get back a flat list of [[IndexedSymbol]]s.
  *
  * Two things bound here are *not* shared by the underlying producers:
  *   - [[TastyIndexer]] returns `Map[SourceUri, (symbols, references)]`; references are dropped at this layer because
  *     dependency JARs are not navigated by reference today.
  *   - [[JavaIndexer]] needs the *origin* JAR path baked in at construction (it tags emitted symbols with
  *     `SymbolOrigin.DependencySource(<origin>)`), and `indexJar` is called with the *sources* JAR — distinct from the
  *     binary JAR. The factory `javaSource(originJarPath)` captures the origin.
  *
  * [[BytecodeIndexer]] is not wrapped — its API is already a flat `IO[List[IndexedSymbol]]` and it doesn't consult a
  * classpath, so `IndexManager` uses it directly as the terminal fallback.
  */
trait SymbolIndexer {
  def indexJar(jar: AbsolutePath, classpath: List[AbsolutePath]): IO[List[IndexedSymbol]]
}

object SymbolIndexer {

  /** TASTy unpickler over `.tasty` entries; type-checked against `classpath`. */
  def tasty(buildTarget: String)(using Tracer[IO]): SymbolIndexer = new SymbolIndexer {
    private val underlying                                                                  = TastyIndexer(buildTarget)
    def indexJar(jar: AbsolutePath, classpath: List[AbsolutePath]): IO[List[IndexedSymbol]] =
      underlying.indexJar(jar, classpath).map(_.values.flatMap(_._1).toList)
  }

  /** dotc Java frontend over `.java` entries in the *sources* JAR. `originJarPath` is the binary jar whose symbols we
    * tag — emitted symbols carry `SymbolOrigin.DependencySource(originJarPath)` even though the JAR fed to `indexJar`
    * is the sources companion.
    */
  def javaSource(originJarPath: String, parallelism: Int = 1): SymbolIndexer = new SymbolIndexer {
    private val underlying = JavaIndexer.forDependency(originJarPath)
    def indexJar(sourcesJar: AbsolutePath, classpath: List[AbsolutePath]): IO[List[IndexedSymbol]] =
      underlying.indexJarEntries(sourcesJar, classpath, parallelism).map(_.values.flatMap(_._1).toList)
  }
}

/** How a single dependency JAR should be indexed. Selected by inspecting the JAR's contents and (optionally) resolving
  * its Maven coordinates via coursier.
  *
  *   - [[Tasty]] — JAR carries `.tasty` entries. Type-check against `classpath` (hermetic when coursier resolves the
  *     jar's POM, project CP otherwise) and fall back to [[Bytecode]] on inspector crash.
  *   - [[JavaSource]] — JAR has Maven coords AND a published `-sources` companion. Type-check the sources JAR against
  *     the hermetically-resolved classpath. Cached by sources-jar SHA-256 since the output depends only on the jar
  *     bytes + extraction logic. Fall back to [[Bytecode]] on failure or zero-symbol result (e.g. a sources jar that
  *     contains only resources).
  *   - [[Bytecode]] — terminal fallback. ASM scan; no classpath, no cache.
  */
enum IndexStrategy {
  case Tasty(classpath: List[AbsolutePath])
  case JavaSource(sourcesJar: AbsolutePath, classpath: List[AbsolutePath])
  case Bytecode
}
