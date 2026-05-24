package org.scala.abusers.sls.index

import cats.effect.IO
import cats.effect.Ref

class DependencyIndex private (state: Ref[IO, DependencyIndex.State]) {

  import DependencyIndex.*

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

  def getSubtypes(id: SymbolId): IO[Set[SymbolId]] =
    state.get.map(_.core.subtypes.getOrElse(id, Set.empty))

  def getSupertypes(id: SymbolId): IO[List[SymbolId]] =
    state.get.map(s => s.core.symbols.get(id).map(_.parents).getOrElse(Nil))

  def addJar(jarPath: String, symbols: List[IndexedSymbol]): IO[Unit] =
    state.update(addJarToState(_, jarPath, symbols))

  def updateLocations(updates: Map[SymbolId, Location]): IO[Unit] =
    state.update { s =>
      val newSymbols = updates.foldLeft(s.core.symbols) { case (syms, (id, loc)) =>
        syms.updatedWith(id)(_.map(_.copy(location = Some(loc))))
      }
      s.copy(core = s.core.copy(symbols = newSymbols))
    }

  def jarMightContain(jarPath: String, name: String): IO[Boolean] =
    state.get.map { s =>
      s.jarFilters.get(jarPath).exists(_.mightContain(name.toLowerCase))
    }

  def symbolCount: IO[Int] = state.get.map(_.core.symbols.size)
  def jarCount: IO[Int]    = state.get.map(_.jarFilters.size)
}

object DependencyIndex {

  def empty: IO[DependencyIndex] =
    Ref[IO].of(State.empty).map(new DependencyIndex(_))

  private[index] case class State(
      core: CoreState,
      jarFilters: Map[String, BloomFilter],
      symbolJar: Map[SymbolId, String],
  )

  private[index] object State {
    val empty: State = State(
      core = CoreState.empty,
      jarFilters = Map.empty,
      symbolJar = Map.empty,
    )
  }

  private def addJarToState(s: State, jarPath: String, newSymbols: List[IndexedSymbol]): State = {
    val bloom = newSymbols.foldLeft(BloomFilter(math.max(newSymbols.size, 16), 0.01)) { (b, sym) =>
      b.add(sym.name.toLowerCase)
    }
    val symbolJar = newSymbols.foldLeft(s.symbolJar)((m, sym) => m + (sym.id -> jarPath))

    s.copy(
      core = CoreState.add(s.core, newSymbols),
      jarFilters = s.jarFilters + (jarPath -> bloom),
      symbolJar = symbolJar,
    )
  }
}
