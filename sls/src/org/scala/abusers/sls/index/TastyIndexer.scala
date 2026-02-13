package org.scala.abusers.sls.index

import cats.effect.IO
import java.net.URI

class TastyIndexer(buildTarget: String) {

  def indexFiles(
      tastyFiles: List[os.Path],
      classpath: List[os.Path],
  ): IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val collector = new SymbolCollector(buildTarget)
      scala.tasty.inspector.TastyInspector.inspectAllTastyFiles(
        tastyFiles.map(_.toString),
        Nil,
        classpath.map(_.toString),
      )(collector)
      collector.result
    }

  def indexDirectory(
      classesDir: os.Path,
      classpath: List[os.Path],
  ): IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val tastyFiles = os.walk(classesDir).filter(_.ext == "tasty").toList
      val collector = new SymbolCollector(buildTarget)
      scala.tasty.inspector.TastyInspector.inspectAllTastyFiles(
        tastyFiles.map(_.toString),
        Nil,
        classpath.map(_.toString),
      )(collector)
      collector.result
    }

  def indexJar(
      jarPath: os.Path,
      classpath: List[os.Path],
  ): IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]] =
    IO.blocking {
      val collector = new SymbolCollector(buildTarget)
      scala.tasty.inspector.TastyInspector.inspectAllTastyFiles(
        Nil,
        List(jarPath.toString),
        classpath.map(_.toString),
      )(collector)
      collector.result
    }
}

private class SymbolCollector(buildTarget: String) extends scala.tasty.inspector.Inspector {
  import scala.collection.mutable

  private val symbols = mutable.Map.empty[URI, mutable.ListBuffer[IndexedSymbol]]
  private val references = mutable.Map.empty[URI, mutable.ListBuffer[SymbolReference]]

  def result: Map[URI, (List[IndexedSymbol], List[SymbolReference])] = {
    val allUris = symbols.keySet ++ references.keySet
    allUris.map { uri =>
      uri -> (
        symbols.getOrElse(uri, mutable.ListBuffer.empty).toList,
        references.getOrElse(uri, mutable.ListBuffer.empty).toList,
      )
    }.toMap
  }

  def inspect(using quotes: scala.quoted.Quotes)(tastys: List[scala.tasty.inspector.Tasty[quotes.type]]): Unit = {
    import quotes.reflect.*

    tastys.foreach { tasty =>
      try processTree(tasty.ast, None)
      catch { case _: Exception => () }
    }

    def processTree(tree: Tree, owner: Option[SymbolId]): Unit = tree match {
      case PackageClause(_, stats) =>
        stats.foreach(processTree(_, owner))

      case cd: ClassDef if !shouldSkipSymbol(cd.symbol) =>
        processClassDef(cd, owner)

      case dd: DefDef if !shouldSkipSymbol(dd.symbol) =>
        processDefDef(dd, owner)

      case vd: ValDef if !shouldSkipSymbol(vd.symbol) =>
        processValDef(vd, owner)

      case td: TypeDef if !shouldSkipSymbol(td.symbol) =>
        processTypeDef(td, owner)

      case t: Term =>
        try extractReferences(t, owner) catch { case _: Exception => () }

      case _ => ()
    }

    def processClassDef(cd: ClassDef, owner: Option[SymbolId]): Unit = {
      val sym = cd.symbol
      val symId = mkSymbolId(sym)
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = classKind(sym)
        val vis = extractVisibility(sym)
        val parents = extractParents(cd)
        val loc = symbolLocation(sym, uri)
        val sig = tryOpt(sym.fullName)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = owner, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = parents, typeSignature = sig,
        ))

        parents.foreach { parentId =>
          loc.foreach(l => addReference(uri, SymbolReference(parentId, l, ReferenceKind.Extends)))
        }

        cd.body.foreach(processTree(_, Some(symId)))
      }
    }

    def processDefDef(dd: DefDef, owner: Option[SymbolId]): Unit = {
      val sym = dd.symbol
      val symId = mkSymbolId(sym)
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = if sym.isClassConstructor then SymbolKind.Constructor else SymbolKind.Method
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = owner, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = Nil, typeSignature = tryOpt(sym.fullName),
        ))

        try {
          sym.allOverriddenSymbols.foreach { overridden =>
            loc.foreach(l => addReference(uri, SymbolReference(mkSymbolId(overridden), l, ReferenceKind.Override)))
          }
        } catch { case _: Exception => () }

        try dd.rhs.foreach(extractReferences(_, Some(symId)))
        catch { case _: Exception => () }
      }
    }

    def processValDef(vd: ValDef, owner: Option[SymbolId]): Unit = {
      val sym = vd.symbol
      val symId = mkSymbolId(sym)
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind =
          if sym.flags.is(Flags.Given) then SymbolKind.Given
          else if sym.flags.is(Flags.Mutable) then SymbolKind.Var
          else SymbolKind.Val
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = owner, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = Nil, typeSignature = tryOpt(sym.fullName),
        ))

        try vd.rhs.foreach(extractReferences(_, Some(symId)))
        catch { case _: Exception => () }
      }
    }

    def processTypeDef(td: TypeDef, owner: Option[SymbolId]): Unit = {
      val sym = td.symbol
      val symId = mkSymbolId(sym)
      val sourceUri = sourceFileUri(sym)

      sourceUri.foreach { uri =>
        val kind = if sym.flags.is(Flags.Param) then SymbolKind.TypeParam else SymbolKind.TypeAlias
        val vis = extractVisibility(sym)
        val loc = symbolLocation(sym, uri)

        addSymbol(uri, IndexedSymbol(
          id = symId, name = sym.name, kind = kind, visibility = vis,
          owner = owner, location = loc,
          origin = SymbolOrigin.ProjectTasty(buildTarget, uri),
          parents = Nil, typeSignature = None,
        ))
      }
    }

    def extractReferences(term: Term, context: Option[SymbolId]): Unit = {
      try {
        term match {
          case Ident(_) if !term.symbol.isNoSymbol =>
            referenceFromTerm(term, ReferenceKind.Call)
          case Select(qual, _) if !term.symbol.isNoSymbol =>
            referenceFromTerm(term, ReferenceKind.Call)
            extractReferences(qual, context)
          case Apply(fn, args) =>
            extractReferences(fn, context)
            args.foreach(extractReferences(_, context))
          case TypeApply(fn, _) =>
            extractReferences(fn, context)
          case Block(stats, expr) =>
            stats.foreach {
              case t: Term => extractReferences(t, context)
              case other   => processTree(other, context)
            }
            extractReferences(expr, context)
          case If(cond, thenp, elsep) =>
            extractReferences(cond, context)
            extractReferences(thenp, context)
            extractReferences(elsep, context)
          case Match(scrutinee, _) =>
            extractReferences(scrutinee, context)
          case Assign(lhs, rhs) =>
            extractReferences(lhs, context)
            extractReferences(rhs, context)
          case Typed(expr, _) =>
            extractReferences(expr, context)
          case _ => ()
        }
      } catch { case _: Exception => () }
    }

    def referenceFromTerm(term: Term, kind: ReferenceKind): Unit = {
      try {
        val sym = term.symbol
        val refId = mkSymbolId(sym)
        val pos = term.pos
        if pos.start != pos.end then {
          posSourceUri(pos).foreach { uri =>
            val loc = Location(uri, pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)
            addReference(uri, SymbolReference(refId, loc, kind))
          }
        }
      } catch { case _: Exception => () }
    }

    def mkSymbolId(sym: Symbol): SymbolId =
      SymbolId(try sym.fullName catch { case _: Exception => sym.name })

    def sourceFileUri(sym: Symbol): Option[URI] =
      try {
        val pos = sym.pos
        if pos.isDefined then {
          val path = pos.get.sourceFile.path
          if path != null then Some(java.nio.file.Path.of(path).toUri)
          else None
        } else None
      } catch { case _: Exception => None }

    def posSourceUri(pos: Position): Option[URI] =
      try {
        val path = pos.sourceFile.path
        if path != null then Some(java.nio.file.Path.of(path).toUri)
        else None
      } catch { case _: Exception => None }

    def symbolLocation(sym: Symbol, uri: URI): Option[Location] =
      try {
        val pos = sym.pos
        if pos.isDefined then {
          val p = pos.get
          Some(Location(uri, p.startLine, p.startColumn, p.endLine, p.endColumn))
        } else None
      } catch { case _: Exception => None }

    def classKind(sym: Symbol): SymbolKind = {
      val flags = sym.flags
      if flags.is(Flags.Trait) then SymbolKind.Trait
      else if flags.is(Flags.Module) then SymbolKind.Object
      else if flags.is(Flags.Enum) && flags.is(Flags.Case) then SymbolKind.EnumCase
      else if flags.is(Flags.Enum) then SymbolKind.Enum
      else SymbolKind.Class
    }

    def extractVisibility(sym: Symbol): Visibility = {
      val flags = sym.flags
      if flags.is(Flags.Private) || flags.is(Flags.PrivateLocal) then Visibility.Private
      else if flags.is(Flags.Protected) then Visibility.Protected
      else Visibility.Public
    }

    def extractParents(cd: ClassDef): List[SymbolId] =
      try {
        cd.parents.flatMap { parent =>
          try {
            val tpe = parent match {
              case t: Term     => t.tpe
              case t: TypeTree => t.tpe
            }
            val typeSym = tpe.typeSymbol
            if typeSym.fullName == "java.lang.Object" || typeSym.fullName == "scala.Any" then None
            else Some(mkSymbolId(typeSym))
          } catch { case _: Exception => None }
        }
      } catch { case _: Exception => Nil }

    def shouldSkipSymbol(sym: Symbol): Boolean =
      try {
        val flags = sym.flags
        val name = sym.name
        flags.is(Flags.Synthetic) || flags.is(Flags.Artifact) ||
        name.contains("$anon") || name.contains("$evidence") || name.startsWith("$")
      } catch { case _: Exception => true }

    def tryOpt[A](a: => A): Option[A] =
      try Some(a) catch { case _: Exception => None }
  }

  private def addSymbol(uri: URI, sym: IndexedSymbol): Unit =
    symbols.getOrElseUpdate(uri, scala.collection.mutable.ListBuffer.empty) += sym

  private def addReference(uri: URI, ref: SymbolReference): Unit =
    references.getOrElseUpdate(uri, scala.collection.mutable.ListBuffer.empty) += ref
}

object TastyIndexer {
  def apply(buildTarget: String): TastyIndexer = new TastyIndexer(buildTarget)
}
