package org.scala.abusers.sls.index

import cats.effect.IO
import cats.effect.Ref
import org.scala.abusers.sls.SourceUri

private[index] class ProjectIndex private (state: Ref[IO, ProjectIndex.State]) {

  import ProjectIndex.*

  def getSymbol(id: SymbolId): IO[Option[IndexedSymbol]] =
    state.get.map(_.core.symbols.get(id))

  def getSymbolsByName(name: String): IO[Set[IndexedSymbol]] =
    state.get.map { s =>
      val lower = name.toLowerCase
      s.core.nameTrie.get(lower).getOrElse(Set.empty).flatMap(s.core.symbols.get)
    }

  def searchSymbols(query: String): IO[List[IndexedSymbol]] =
    state.get.map { s =>
      val ids =
        if CamelCaseUtils.isCamelCaseQuery(query) then {
          val ccKey = query.toLowerCase
          s.core.camelCaseTrie.prefixSearch(ccKey).flatMap(_._2).toSet
        } else {
          val lower = query.toLowerCase
          s.core.nameTrie.prefixSearch(lower).flatMap(_._2).toSet
        }
      ids.flatMap(s.core.symbols.get).toList
    }

  def getSymbolsInFile(uri: SourceUri): IO[Set[IndexedSymbol]] =
    state.get.map { s =>
      s.byFile.getOrElse(uri, Set.empty).flatMap(s.core.symbols.get)
    }

  def getReferences(id: SymbolId): IO[List[SymbolReference]] =
    state.get.map(_.references.getOrElse(id, Nil))

  def getReferencesInFile(uri: SourceUri): IO[List[SymbolReference]] =
    state.get.map(_.refsByFile.getOrElse(uri, Nil))

  def getSubtypes(id: SymbolId): IO[Set[SymbolId]] =
    state.get.map(_.core.subtypes.getOrElse(id, Set.empty))

  def getSupertypes(id: SymbolId): IO[List[SymbolId]] =
    state.get.map(s => s.core.symbols.get(id).map(_.parents).getOrElse(Nil))

  def updateFiles(files: Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]): IO[Unit] =
    state.update { s =>
      val totalRefs = files.values.map(_._2.size).sum
      logger.info(
        s"updateFiles: ${files.size} files, ${files.values.map(_._1.size).sum} symbols, $totalRefs refs. Before: ${s.references.size} ref keys, ${s.core.symbols.size} symbols"
      )
      val result = files.foldLeft(s) { case (state, (uri, (symbols, refs))) =>
        val cleaned = removeFileFromState(state, uri)
        addFileToState(cleaned, uri, symbols, refs)
      }
      logger.info(s"updateFiles done. After: ${result.references.size} ref keys, ${result.core.symbols.size} symbols")
      result
    }

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  def removeFiles(uris: Set[SourceUri]): IO[Unit] =
    state.update { s =>
      logger.info(
        s"removeFiles called with ${uris.size} URIs, current refs=${s.references.size} keys, symbols=${s.core.symbols.size}"
      )
      uris.foldLeft(s)(removeFileFromState)
    }

  def symbolCount: IO[Int]                    = state.get.map(_.core.symbols.size)
  def fileCount: IO[Int]                      = state.get.map(_.byFile.size)
  def allReferenceTargets: IO[List[SymbolId]] = state.get.map(_.references.keys.toList)
}

object ProjectIndex {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[ProjectIndex])

  def empty: IO[ProjectIndex] =
    Ref[IO].of(State.empty).map(new ProjectIndex(_))

  private[index] case class State(
      core: CoreState,
      byFile: Map[SourceUri, Set[SymbolId]],
      references: Map[SymbolId, List[SymbolReference]],
      refsByFile: Map[SourceUri, List[SymbolReference]],
  )

  private[index] object State {
    val empty: State = State(
      core = CoreState.empty,
      byFile = Map.empty,
      references = Map.empty,
      refsByFile = Map.empty,
    )
  }

  private def removeFileFromState(s: State, uri: SourceUri): State = {
    val oldIds = s.byFile.getOrElse(uri, Set.empty)
    if oldIds.isEmpty && !s.refsByFile.contains(uri) then return s

    val oldRefs = s.refsByFile.getOrElse(uri, Nil)
    val refsMap = oldRefs.foldLeft(s.references) { (m, ref) =>
      m.updatedWith(ref.symbol) {
        case Some(list) =>
          val filtered = list.filterNot(_.location.uri == uri)
          if filtered.isEmpty then None else Some(filtered)
        case None => None
      }
    }

    s.copy(
      core = CoreState.remove(s.core, oldIds),
      byFile = s.byFile - uri,
      references = refsMap,
      refsByFile = s.refsByFile - uri,
    )
  }

  private def addFileToState(
      s: State,
      uri: SourceUri,
      newSymbols: List[IndexedSymbol],
      newRefs: List[SymbolReference],
  ): State = {
    val refsMap = newRefs.foldLeft(s.references) { (m, ref) =>
      logger.info(
        s"  addRef: ${ref.symbol.render} -> ${ref.location.uri}:${ref.location.startLine}:${ref.location.startCol} (${ref.referenceKind})"
      )
      m.updatedWith(ref.symbol)(_.fold(Some(List(ref)))(list => Some(ref :: list)))
    }

    s.copy(
      core = CoreState.add(s.core, newSymbols),
      byFile = s.byFile + (uri -> newSymbols.map(_.id).toSet),
      references = refsMap,
      refsByFile = s.refsByFile + (uri -> newRefs),
    )
  }
}
