package org.scala.abusers.sls.index

import cats.effect.{IO, Ref}

class DependencyIndex private (state: Ref[IO, DependencyIndex.State]) {

  import DependencyIndex.*

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

  def getSubtypes(id: SymbolId): IO[Set[SymbolId]] =
    state.get.map(_.subtypes.getOrElse(id, Set.empty))

  def getSupertypes(id: SymbolId): IO[List[SymbolId]] =
    state.get.map(s => s.symbols.get(id).map(_.parents).getOrElse(Nil))

  def addJar(jarPath: String, symbols: List[IndexedSymbol]): IO[Unit] =
    state.update(addJarToState(_, jarPath, symbols))

  def updateLocations(updates: Map[SymbolId, Location]): IO[Unit] =
    state.update { s =>
      val newSymbols = updates.foldLeft(s.symbols) { case (syms, (id, loc)) =>
        syms.updatedWith(id)(_.map(_.copy(location = Some(loc))))
      }
      s.copy(symbols = newSymbols)
    }

  def jarMightContain(jarPath: String, name: String): IO[Boolean] =
    state.get.map { s =>
      s.jarFilters.get(jarPath).exists(_.mightContain(name.toLowerCase))
    }
}

object DependencyIndex {

  def empty: IO[DependencyIndex] =
    Ref[IO].of(State.empty).map(new DependencyIndex(_))

  private[index] case class State(
      symbols: Map[SymbolId, IndexedSymbol],
      nameTrie: PatriciaTrie[Set[SymbolId]],
      camelCaseTrie: PatriciaTrie[Set[SymbolId]],
      subtypes: Map[SymbolId, Set[SymbolId]],
      jarFilters: Map[String, BloomFilter],
      symbolJar: Map[SymbolId, String],
  )

  private[index] object State {
    val empty: State = State(
      symbols = Map.empty,
      nameTrie = PatriciaTrie.empty,
      camelCaseTrie = PatriciaTrie.empty,
      subtypes = Map.empty,
      jarFilters = Map.empty,
      symbolJar = Map.empty,
    )
  }

  private def addJarToState(s: State, jarPath: String, newSymbols: List[IndexedSymbol]): State = {
    var symbols = s.symbols
    var nameTrie = s.nameTrie
    var camelCaseTrie = s.camelCaseTrie
    var subtypesMap = s.subtypes
    var symbolJar = s.symbolJar
    var bloom = BloomFilter(math.max(newSymbols.size, 16), 0.01)

    newSymbols.foreach { sym =>
      symbols = symbols + (sym.id -> sym)
      symbolJar = symbolJar + (sym.id -> jarPath)

      val lower = sym.name.toLowerCase
      bloom = bloom.add(lower)

      val nameSet = nameTrie.get(lower).getOrElse(Set.empty)
      nameTrie = nameTrie.insert(lower, nameSet + sym.id)

      val cc = CamelCaseUtils.extractCamelCase(sym.name)
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

    s.copy(
      symbols = symbols,
      nameTrie = nameTrie,
      camelCaseTrie = camelCaseTrie,
      subtypes = subtypesMap,
      jarFilters = s.jarFilters + (jarPath -> bloom),
      symbolJar = symbolJar,
    )
  }
}
