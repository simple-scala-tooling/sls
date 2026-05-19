package org.scala.abusers.sls.index

import cats.effect.IO
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.CompilationUnit
import org.scala.abusers.sls.toSourceUri
import org.scala.abusers.sls.AbsolutePath
import org.scala.abusers.sls.SourceUri
import org.slf4j.LoggerFactory

import scala.collection.mutable

/** Indexes `.java` source files by running them through [[JavaFrontendDriver]] (Parser → TyperPhase) and harvesting the
  * resulting class/method/field symbols. Java parsing only produces outlines (no method bodies), so references are
  * limited to `extends`/`implements` relationships.
  */
class JavaIndexer(buildTarget: String) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def indexFiles(
      javaFiles: List[AbsolutePath],
      classpath: List[AbsolutePath],
  ): IO[Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val collector = new JavaSymbolCollector(buildTarget)
      try
        JavaFrontendDriver
          .inspect(javaFiles.map(_.toNioPath.toString), classpath.map(_.toNioPath.toString))(collector)
      catch {
        case t: Throwable =>
          logger.error(s"Java indexer crash for ${javaFiles.take(3).map(_.toNioPath).mkString(", ")}", t)
      }
      collector.result
    }
}

object JavaIndexer {
  def apply(buildTarget: String): JavaIndexer = new JavaIndexer(buildTarget)
}

private class JavaSymbolCollector(buildTarget: String) extends JavaInspector {
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
          origin = SymbolOrigin.ProjectJavaSource(buildTarget, uri),
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
          origin = SymbolOrigin.ProjectJavaSource(buildTarget, uri),
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
          origin = SymbolOrigin.ProjectJavaSource(buildTarget, uri),
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
