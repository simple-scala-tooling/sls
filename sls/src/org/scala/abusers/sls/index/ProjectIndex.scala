package org.scala.abusers.sls.index

import cats.effect.{IO, Ref}
import org.scala.abusers.sls.SourceUri

class ProjectIndex private (state: Ref[IO, ProjectIndex.State]) {

  import ProjectIndex.*

  def getSymbol(id: SymbolId): IO[Option[IndexedSymbol]] =
    state.get.map(_.symbols.get(id))

  def getSymbolsByName(name: String): IO[Set[IndexedSymbol]] =
    state.get.map { s =>
      val lower = name.toLowerCase
      s.nameTrie.get(lower).getOrElse(Set.empty).flatMap(s.symbols.get)
    }

  def searchSymbols(query: String): IO[List[IndexedSymbol]] =
    state.get.map { s =>
      val ids =
        if CamelCaseUtils.isCamelCaseQuery(query) then {
          val ccKey = query.toLowerCase
          s.camelCaseTrie.prefixSearch(ccKey).flatMap(_._2).toSet
        } else {
          val lower = query.toLowerCase
          s.nameTrie.prefixSearch(lower).flatMap(_._2).toSet
        }
      ids.flatMap(s.symbols.get).toList
    }

  def getSymbolsInFile(uri: SourceUri): IO[Set[IndexedSymbol]] =
    state.get.map { s =>
      s.byFile.getOrElse(uri, Set.empty).flatMap(s.symbols.get)
    }

  def getReferences(id: SymbolId): IO[List[SymbolReference]] =
    state.get.map(_.references.getOrElse(id, Nil))

  def getReferencesInFile(uri: SourceUri): IO[List[SymbolReference]] =
    state.get.map(_.refsByFile.getOrElse(uri, Nil))

  def getSubtypes(id: SymbolId): IO[Set[SymbolId]] =
    state.get.map(_.subtypes.getOrElse(id, Set.empty))

  def getSupertypes(id: SymbolId): IO[List[SymbolId]] =
    state.get.map(s => s.symbols.get(id).map(_.parents).getOrElse(Nil))

  def updateFiles(files: Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])]): IO[Unit] =
    state.update { s =>
      val totalRefs = files.values.map(_._2.size).sum
      logger.info(s"updateFiles: ${files.size} files, ${files.values.map(_._1.size).sum} symbols, $totalRefs refs. Before: ${s.references.size} ref keys, ${s.symbols.size} symbols")
      val result = files.foldLeft(s) { case (state, (uri, (symbols, refs))) =>
        val cleaned = removeFileFromState(state, uri)
        addFileToState(cleaned, uri, symbols, refs)
      }
      logger.info(s"updateFiles done. After: ${result.references.size} ref keys, ${result.symbols.size} symbols")
      result
    }

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  def removeFiles(uris: Set[SourceUri]): IO[Unit] =
    state.update { s =>
      logger.info(s"removeFiles called with ${uris.size} URIs, current refs=${s.references.size} keys, symbols=${s.symbols.size}")
      uris.foldLeft(s)(removeFileFromState)
    }

  def symbolCount: IO[Int] = state.get.map(_.symbols.size)
  def fileCount: IO[Int] = state.get.map(_.byFile.size)
  def debugReferenceKeys: IO[List[SymbolId]] = state.get.map(_.references.keys.toList)
}

object ProjectIndex {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[ProjectIndex])

  def empty: IO[ProjectIndex] =
    Ref[IO].of(State.empty).map(new ProjectIndex(_))

  private[index] case class State(
      symbols: Map[SymbolId, IndexedSymbol],
      nameTrie: PatriciaTrie[Set[SymbolId]],
      camelCaseTrie: PatriciaTrie[Set[SymbolId]],
      byFile: Map[SourceUri, Set[SymbolId]],
      references: Map[SymbolId, List[SymbolReference]],
      refsByFile: Map[SourceUri, List[SymbolReference]],
      subtypes: Map[SymbolId, Set[SymbolId]],
  )

  private[index] object State {
    val empty: State = State(
      symbols = Map.empty,
      nameTrie = PatriciaTrie.empty,
      camelCaseTrie = PatriciaTrie.empty,
      byFile = Map.empty,
      references = Map.empty,
      refsByFile = Map.empty,
      subtypes = Map.empty,
    )
  }

  private def removeFileFromState(s: State, uri: SourceUri): State = {
    val oldIds = s.byFile.getOrElse(uri, Set.empty)
    if oldIds.isEmpty && !s.refsByFile.contains(uri) then return s

    var symbols = s.symbols
    var nameTrie = s.nameTrie
    var camelCaseTrie = s.camelCaseTrie
    var subtypesMap = s.subtypes

    oldIds.foreach { id =>
      s.symbols.get(id).foreach { sym =>
        symbols = symbols - id

        val lower = sym.name.toLowerCase
        nameTrie = nameTrie.update(lower, _ - id)

        val cc = CamelCaseUtils.extractPascalCase(sym.name)
        if cc.nonEmpty then camelCaseTrie = camelCaseTrie.update(cc, _ - id)

        sym.parents.foreach { parent =>
          subtypesMap = subtypesMap.updatedWith(parent)(_.map(_ - id).filter(_.nonEmpty))
        }
      }
    }

    // Remove references for this file
    val oldRefs = s.refsByFile.getOrElse(uri, Nil)
    var refsMap = s.references
    oldRefs.foreach { ref =>
      refsMap = refsMap.updatedWith(ref.symbol) {
        case Some(list) =>
          val filtered = list.filterNot(r => r.location.uri == uri)
          if filtered.isEmpty then None else Some(filtered)
        case None => None
      }
    }

    s.copy(
      symbols = symbols,
      nameTrie = nameTrie,
      camelCaseTrie = camelCaseTrie,
      byFile = s.byFile - uri,
      references = refsMap,
      refsByFile = s.refsByFile - uri,
      subtypes = subtypesMap,
    )
  }

  private def addFileToState(
      s: State,
      uri: SourceUri,
      newSymbols: List[IndexedSymbol],
      newRefs: List[SymbolReference],
  ): State = {
    var symbols = s.symbols
    var nameTrie = s.nameTrie
    var camelCaseTrie = s.camelCaseTrie
    var subtypesMap = s.subtypes
    val ids = Set.newBuilder[SymbolId]

    newSymbols.foreach { sym =>
      symbols = symbols + (sym.id -> sym)
      ids += sym.id

      val lower = sym.name.toLowerCase
      val nameSet = nameTrie.get(lower).getOrElse(Set.empty)
      nameTrie = nameTrie.insert(lower, nameSet + sym.id)

      val cc = CamelCaseUtils.extractPascalCase(sym.name)
      if cc.nonEmpty then {
        val ccSet = camelCaseTrie.get(cc).getOrElse(Set.empty)
        camelCaseTrie = camelCaseTrie.insert(cc, ccSet + sym.id)
      }

      sym.parents.foreach { parent =>
        subtypesMap = subtypesMap.updatedWith(parent) {
          case Some(set) => Some(set + sym.id)
          case None      => Some(Set(sym.id))
        }
      }
    }

    var refsMap = s.references
    newRefs.foreach { ref =>
      logger.info(s"  addRef: ${ref.symbol.value} -> ${ref.location.uri}:${ref.location.startLine}:${ref.location.startCol} (${ref.referenceKind})")
      refsMap = refsMap.updatedWith(ref.symbol) {
        case Some(list) => Some(ref :: list)
        case None       => Some(List(ref))
      }
    }

    s.copy(
      symbols = symbols,
      nameTrie = nameTrie,
      camelCaseTrie = camelCaseTrie,
      byFile = s.byFile + (uri -> ids.result()),
      references = refsMap,
      refsByFile = s.refsByFile + (uri -> newRefs),
      subtypes = subtypesMap,
    )
  }
}
