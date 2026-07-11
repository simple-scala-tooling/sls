package org.scala.abusers.sls.util

import cats.effect.IO
import org.scala.abusers.sls.index.util.TestWorkspace
import org.scala.abusers.sls.index.SymbolId

/** Oracles for the convergence invariant (`ai/test-plan.md` H4): state built up over an editing session must equal a
  * fresh build of the final content — otherwise "restart the server" would fix something, which is the bug.
  */
object Convergence {

  /** Symbol ids of a fresh index compiled from `sources` — the oracle side. */
  def freshIndexIds(sources: (String, String)*): IO[Set[SymbolId]] =
    TestWorkspace.withSources(sources*).use(_.symbolIndex.flatMap(_.searchSymbols("").map(_.map(_.id).toSet)))

  /** Symbol ids accumulated by the session under test. */
  def sessionIndexIds(h: TestServer.Handle): IO[Set[SymbolId]] =
    h.symbolIndex.searchSymbols("").map(_.map(_.id).toSet)
}
