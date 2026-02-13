package org.scala.abusers.sls.index

import cats.effect.IO
import java.net.URI

class SymbolIndex(
    val project: ProjectIndex,
    val dependency: DependencyIndex,
) {

  def getSymbol(id: SymbolId): IO[Option[IndexedSymbol]] =
    project.getSymbol(id).flatMap {
      case some @ Some(_) => IO.pure(some)
      case None           => dependency.getSymbol(id)
    }

  def getSymbolsByName(name: String): IO[Set[IndexedSymbol]] =
    for {
      proj <- project.getSymbolsByName(name)
      dep <- dependency.getSymbolsByName(name)
      projIds = proj.map(_.id)
    } yield proj ++ dep.filterNot(s => projIds.contains(s.id))

  def searchSymbols(query: String): IO[List[IndexedSymbol]] =
    for {
      proj <- project.searchSymbols(query)
      dep <- dependency.searchSymbols(query)
      projIds = proj.map(_.id).toSet
    } yield proj ++ dep.filterNot(s => projIds.contains(s.id))

  def getReferences(id: SymbolId): IO[List[SymbolReference]] =
    project.getReferences(id)

  def getSubtypes(id: SymbolId): IO[Set[SymbolId]] =
    for {
      proj <- project.getSubtypes(id)
      dep <- dependency.getSubtypes(id)
    } yield proj ++ dep

  def getSupertypes(id: SymbolId): IO[List[SymbolId]] =
    getSymbol(id).map {
      case Some(sym) => sym.parents
      case None      => Nil
    }

  def getImplementations(id: SymbolId, maxDepth: Int = 10): IO[Set[IndexedSymbol]] =
    getTransitiveSubtypes(Set(id), Set.empty, maxDepth).flatMap { ids =>
      ids.toList.traverse(getSymbol).map(_.flatten.filter(isConcrete).toSet)
    }

  def resolveSymbolAtPosition(uri: URI, line: Int, col: Int): IO[Option[IndexedSymbol]] =
    project.getSymbolsInFile(uri).map { symbols =>
      symbols.toList
        .filter(s => containsPosition(s.location, line, col))
        .sortBy(s => locationArea(s.location))(using Ordering[Long])
        .headOption
    }

  private def getTransitiveSubtypes(
      frontier: Set[SymbolId],
      visited: Set[SymbolId],
      remaining: Int,
  ): IO[Set[SymbolId]] =
    if frontier.isEmpty || remaining <= 0 then IO.pure(visited)
    else
      frontier.toList.traverse(getSubtypes).map(_.foldLeft(Set.empty[SymbolId])(_ ++ _)).flatMap {
        next =>
          val newIds = next -- visited
          getTransitiveSubtypes(newIds, visited ++ newIds, remaining - 1)
      }

  private def isConcrete(sym: IndexedSymbol): Boolean =
    sym.kind == SymbolKind.Class || sym.kind == SymbolKind.Object ||
      sym.kind == SymbolKind.Enum || sym.kind == SymbolKind.EnumCase

  private def containsPosition(loc: Option[Location], line: Int, col: Int): Boolean =
    loc.exists { l =>
      (l.startLine < line || (l.startLine == line && l.startCol <= col)) &&
      (l.endLine > line || (l.endLine == line && l.endCol >= col))
    }

  private def locationArea(loc: Option[Location]): Long =
    loc.map { l =>
      val lines = (l.endLine - l.startLine).toLong
      val cols = (l.endCol - l.startCol).toLong
      lines * 10000 + cols
    }.getOrElse(Long.MaxValue)

  // cats traverse for List isn't imported, inline it
  private implicit class ListTraverseOps[A](list: List[A]) {
    def traverse[B](f: A => IO[B]): IO[List[B]] =
      list.foldRight(IO.pure(List.empty[B])) { (a, acc) =>
        f(a).flatMap(b => acc.map(b :: _))
      }
  }
}

object SymbolIndex {
  def apply(project: ProjectIndex, dependency: DependencyIndex): SymbolIndex =
    new SymbolIndex(project, dependency)
}
