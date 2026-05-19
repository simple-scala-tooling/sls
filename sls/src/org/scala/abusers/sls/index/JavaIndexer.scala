package org.scala.abusers.sls.index

import cats.effect.IO
import cats.effect.Resource
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.CompilationUnit
import dotty.tools.io.AbstractFile
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri
import org.slf4j.LoggerFactory

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import scala.io.Codec
import scala.jdk.CollectionConverters.*

/** Indexes `.java` source files by running them through [[JavaFrontendDriver]] (Parser → TyperPhase) and harvesting the
  * resulting class/method/field symbols. Java parsing only produces outlines (no method bodies), so references are
  * limited to `extends`/`implements` relationships.
  *
  * `originFor` decides how each emitted symbol is tagged — project Java source vs. dependency source jar.
  */
class JavaIndexer(originFor: SourceUri => SymbolOrigin) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def indexFiles(
      javaFiles: List[AbsolutePath],
      classpath: List[AbsolutePath],
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val collector = new JavaSymbolCollector(originFor)
      try
        JavaFrontendDriver
          .inspect(javaFiles.map(_.toNioPath.toString), classpath.map(_.toNioPath.toString))(collector)
      catch {
        case t: Throwable =>
          logger.error(s"Java indexer crash for ${javaFiles.take(3).map(_.toNioPath).mkString(", ")}", t)
      }
      collector.result
    }

  /** Index all `.java` entries inside `jarPath` without extracting them — opens a zip [[java.nio.file.FileSystem]]
    * over the jar and feeds dotc [[SourceFile]]s backed by `ZipPath`s. When `parallelism > 1`, partitions entries by
    * their top-level path component (e.g. JDK module name in `src.zip`, or top-level package in a dep jar) and runs
    * that many dotc instances concurrently. Each instance is independent — dotc's frontend doesn't share mutable
    * state across `Run` constructions.
    *
    * The shared FileSystem stays open across all parallel workers (cats-effect `Resource`), so `ZipPath`s remain
    * valid in every chunk.
    */
  def indexJarEntries(
      jarPath: AbsolutePath,
      classpath: List[AbsolutePath],
      parallelism: Int = 1,
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] = {
    val cpStrings = classpath.map(_.toNioPath.toString)
    Resource
      .fromAutoCloseable(IO.blocking(FileSystems.newFileSystem(jarPath.toNioPath, null: ClassLoader)))
      .use { fs =>
        IO.blocking {
          fs.getRootDirectories.asScala.toList.flatMap { root =>
            Files
              .walk(root)
              .iterator
              .asScala
              .filter { p =>
                !Files.isDirectory(p) &&
                p.toString.endsWith(".java") &&
                // module-info.java uses module declaration syntax that dotc's JavaParser can't parse, and it
                // contributes no symbols worth indexing.
                p.getFileName.toString != "module-info.java"
              }
              .toList
          }
        }.flatMap { entries =>
          if entries.isEmpty then IO.pure(Map.empty)
          else if parallelism <= 1 then runChunk(entries, cpStrings, jarPath)
          else {
            val chunks = entries.groupBy(topLevelKey).values.toList
            fs2.Stream
              .emits(chunks)
              .parEvalMapUnordered(parallelism)(runChunk(_, cpStrings, jarPath))
              .compile
              .toList
              .map(_.foldLeft(Map.empty)(_ ++ _))
          }
        }
      }
  }

  private def runChunk(
      paths: List[Path],
      cpStrings: List[String],
      jarPath: AbsolutePath,
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] = IO.blocking {
    val collector = new JavaSymbolCollector(originFor)
    try {
      val sources = paths.map(p => SourceFile(AbstractFile.getFile(p), Codec.UTF8))
      JavaFrontendDriver.inspectSources(sources, cpStrings)(collector)
    } catch {
      case t: Throwable =>
        logger.error(s"Java indexer crash for chunk of ${paths.size} entries in $jarPath", t)
    }
    collector.result
  }

  private def topLevelKey(p: Path): String =
    if p.getNameCount > 0 then p.getName(0).toString else ""
}

object JavaIndexer {
  def forProject(buildTarget: String): JavaIndexer =
    new JavaIndexer(uri => SymbolOrigin.ProjectJavaSource(buildTarget, uri))

  def forDependency(jarPath: String): JavaIndexer =
    new JavaIndexer(uri => SymbolOrigin.DependencySource(jarPath, uri))

  def forJdk(srcZipPath: String): JavaIndexer =
    new JavaIndexer(uri => SymbolOrigin.JdkSource(srcZipPath, uri))
}

private class JavaSymbolCollector(originFor: SourceUri => SymbolOrigin) extends JavaInspector {
  import tpd.TreeOps

  private val symbols    = mutable.Map.empty[SourceUri, mutable.ListBuffer[IndexedSymbol]]
  private val references = mutable.Map.empty[SourceUri, mutable.ListBuffer[SymbolReference]]

  def result: Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])] = {
    val allUris = symbols.keySet ++ references.keySet
    allUris.map { uri =>
      uri -> (
        symbols.getOrElse(uri, mutable.ListBuffer.empty).toList,
        references.getOrElse(uri, mutable.ListBuffer.empty).toList,
      )
    }.toMap
  }

  def inspect(units: List[CompilationUnit])(using Context): Unit =
    units.foreach { unit =>
      unit.tpdTree.foreachSubTree {
        case td: tpd.TypeDef if td.symbol.isClass && !skipClass(td.symbol) => processClass(td)
        case dd: tpd.DefDef if !skipMember(dd.symbol)                      => processDef(dd)
        case vd: tpd.ValDef if !skipMember(vd.symbol)                      => processVal(vd)
        case _                                                             => ()
      }
    }

  /** Skip synthetic module class emitted by dotty alongside Java classes (e.g. `Square$`). The user wrote `Square`;
    * `Square$` is dotty's static-side container.
    */
  private def skipClass(sym: Symbol)(using Context): Boolean =
    sym.is(Flags.Module) || sym.is(Flags.Synthetic)

  /** Skip synthetic / parameter / module-value members: constructor-param mirrors, the lazy `val Square` that points
    * at the companion module, and anything marked synthetic.
    */
  private def skipMember(sym: Symbol)(using Context): Boolean =
    sym.is(Flags.Synthetic) ||
      sym.is(Flags.Param) ||
      sym.is(Flags.Module) ||
      sym.name.toString.startsWith("_$") ||
      sym.name.toString.startsWith("x$")

  private def processClass(td: tpd.TypeDef)(using Context): Unit = {
    val sym = td.symbol.asClass
    sourceUri(sym).foreach { uri =>
      val parentSyms = td.rhs match {
        case tmpl: tpd.Template => tmpl.parents.flatMap(parentSymbol)
        case _                  => Nil
      }
      val parentIds = parentSyms.map(symbolId).distinct

      addSymbol(
        uri,
        IndexedSymbol(
          id = symbolId(sym),
          name = sym.name.toString,
          kind = classKind(sym),
          visibility = visibility(sym),
          owner = ownerId(sym),
          location = symbolLocation(sym, uri),
          origin = originFor(uri),
          parents = parentIds,
          typeSignature = Some(sym.fullName.toString),
        ),
      )

      symbolLocation(sym, uri).foreach { loc =>
        parentIds.foreach(pid => addReference(uri, SymbolReference(pid, loc, ReferenceKind.Extends)))
      }
    }
  }

  private def processDef(dd: tpd.DefDef)(using Context): Unit = {
    val sym = dd.symbol
    sourceUri(sym).foreach { uri =>
      addSymbol(
        uri,
        IndexedSymbol(
          id = symbolId(sym),
          name = sym.name.toString,
          kind = if sym.isClassConstructor then SymbolKind.Constructor else SymbolKind.Method,
          visibility = visibility(sym),
          owner = ownerId(sym),
          location = symbolLocation(sym, uri),
          origin = originFor(uri),
          parents = Nil,
          typeSignature = Some(sym.info.show),
        ),
      )
    }
  }

  private def processVal(vd: tpd.ValDef)(using Context): Unit = {
    val sym = vd.symbol
    sourceUri(sym).foreach { uri =>
      addSymbol(
        uri,
        IndexedSymbol(
          id = symbolId(sym),
          name = sym.name.toString,
          kind = SymbolKind.Field,
          visibility = visibility(sym),
          owner = ownerId(sym),
          location = symbolLocation(sym, uri),
          origin = originFor(uri),
          parents = Nil,
          typeSignature = Some(sym.info.show),
        ),
      )
    }
  }

  private def parentSymbol(parent: tpd.Tree)(using Context): Option[Symbol] = {
    val sym = parent.tpe.typeSymbol
    if sym.exists && sym.fullName.toString != "java.lang.Object" then Some(sym) else None
  }

  private def classKind(sym: Symbol)(using Context): SymbolKind = {
    val flags = sym.flags
    if flags.is(Flags.Enum) then SymbolKind.Enum
    else if flags.is(Flags.Trait) then SymbolKind.Trait // Java interface
    else SymbolKind.Class
  }

  private def visibility(sym: Symbol)(using Context): Visibility = {
    val flags = sym.flags
    if flags.is(Flags.Private) || flags.isAllOf(Flags.PrivateLocal) then Visibility.Private
    else if flags.is(Flags.Protected) then Visibility.Protected
    else if flags.is(Flags.Package) then Visibility.PackagePrivate
    else Visibility.Public
  }

  private def symbolId(sym: Symbol)(using Context): SymbolId = SymbolId(sym.fullName.toString)

  private def ownerId(sym: Symbol)(using Context): Option[SymbolId] = {
    val o = sym.owner
    if !o.exists || o.isRoot then None else Some(symbolId(o))
  }

  private def sourceUri(sym: Symbol)(using Context): Option[SourceUri] = {
    val sf = sym.source
    if sf.exists && sf.path != null then Some(java.nio.file.Path.of(sf.path).toSourceUri) else None
  }

  private def symbolLocation(sym: Symbol, uri: SourceUri)(using Context): Option[Location] = {
    val pos: SourcePosition = sym.sourcePos
    if pos.exists then
      Some(Location(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn))
    else None
  }

  private def addSymbol(uri: SourceUri, sym: IndexedSymbol): Unit =
    symbols.getOrElseUpdate(uri, mutable.ListBuffer.empty) += sym

  private def addReference(uri: SourceUri, ref: SymbolReference): Unit =
    references.getOrElseUpdate(uri, mutable.ListBuffer.empty) += ref
}
