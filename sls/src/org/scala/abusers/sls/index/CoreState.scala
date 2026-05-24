package org.scala.abusers.sls.index

private[index] case class CoreState(
    symbols: Map[SymbolId, IndexedSymbol],
    nameTrie: PatriciaTrie[Set[SymbolId]],
    camelCaseTrie: PatriciaTrie[Set[SymbolId]],
    subtypes: Map[SymbolId, Set[SymbolId]],
)

private[index] object CoreState {

  val empty: CoreState = CoreState(
    symbols = Map.empty,
    nameTrie = PatriciaTrie.empty,
    camelCaseTrie = PatriciaTrie.empty,
    subtypes = Map.empty,
  )

  def add(s: CoreState, syms: List[IndexedSymbol]): CoreState =
    syms.foldLeft(s) { (acc, sym) =>
      val lower = sym.name.toLowerCase
      val cc    = CamelCaseUtils.extractPascalCase(sym.name)

      val nameTrie      = insertId(acc.nameTrie, lower, sym.id)
      val camelCaseTrie = if cc.isEmpty then acc.camelCaseTrie else insertId(acc.camelCaseTrie, cc, sym.id)
      val subtypes      = sym.parents.foldLeft(acc.subtypes) { (m, parent) =>
        m.updatedWith(parent)(_.fold(Some(Set(sym.id)))(set => Some(set + sym.id)))
      }

      acc.copy(
        symbols = acc.symbols + (sym.id -> sym),
        nameTrie = nameTrie,
        camelCaseTrie = camelCaseTrie,
        subtypes = subtypes,
      )
    }

  def updateLocations(s: CoreState, updates: Map[SymbolId, Location]): CoreState = {
    val newSymbols = updates.foldLeft(s.symbols) { case (syms, (id, loc)) =>
      syms.updatedWith(id)(_.map(_.copy(location = Some(loc))))
    }
    s.copy(symbols = newSymbols)
  }

  def remove(s: CoreState, ids: Set[SymbolId]): CoreState =
    ids.foldLeft(s) { (acc, id) =>
      acc.symbols.get(id) match {
        case None      => acc
        case Some(sym) =>
          val lower = sym.name.toLowerCase
          val cc    = CamelCaseUtils.extractPascalCase(sym.name)

          val nameTrie      = removeId(acc.nameTrie, lower, id)
          val camelCaseTrie = if cc.isEmpty then acc.camelCaseTrie else removeId(acc.camelCaseTrie, cc, id)
          val subtypes      = sym.parents.foldLeft(acc.subtypes) { (m, parent) =>
            m.updatedWith(parent)(_.map(_ - id).filter(_.nonEmpty))
          }

          acc.copy(
            symbols = acc.symbols - id,
            nameTrie = nameTrie,
            camelCaseTrie = camelCaseTrie,
            subtypes = subtypes,
          )
      }
    }

  private def insertId(trie: PatriciaTrie[Set[SymbolId]], key: String, id: SymbolId): PatriciaTrie[Set[SymbolId]] =
    trie.insert(key, trie.get(key).getOrElse(Set.empty) + id)

  private def removeId(trie: PatriciaTrie[Set[SymbolId]], key: String, id: SymbolId): PatriciaTrie[Set[SymbolId]] =
    trie.get(key) match {
      case None      => trie
      case Some(set) =>
        val next = set - id
        if next.isEmpty then trie.remove(key) else trie.insert(key, next)
    }
}
