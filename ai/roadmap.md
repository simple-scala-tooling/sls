# SLS Roadmap — index hardening, features, and the worker split

Single source of truth for planned work. Supersedes `index-plan.md`, `index-tasks.md`,
`index-progress.md`, and `architecture-improvements.md` (all deleted; history is in git).
Still-current companion docs:

- `ai/test-infrastructure.md` — detailed spec for the `code"""…"""` DSL, `TestWorkspace`, `TestClient` (Phase 1 here)
- `ai/tasty-indexer-crashes.md` — reference: why shaded jars crash the TASTy inspector and how the fallback handles it

## Where we are

Index phases 0–5 of the previous plans are done and merged (canonical `SymbolId` +
cross-producer spec, `SymbolIndexer`/`IndexStrategy`, `CoreState` dedup, sealed module
boundary, lifecycle readiness model with `Cold/Bootstrapping/Ready/Failed`). The current
branch (`index/phase6-polish`) wires `CompileOutput.outputFormat` through CSP and cleans up
`TastyIndexer`. LSP feature handlers beyond references are stubs.

A design review (June 2026) identified the gaps this roadmap addresses. Ordering decision:
**stabilize first, then features; build seams now, split processes later.**

## Design principles (decided, don't relitigate without cause)

1. **Single index owner, single writer.** Exactly one process owns the index (the LSP
   process, for now and after the worker split). All writes are sequenced; an older snapshot
   of a file must never overwrite a newer one. Other processes query via RPC over the
   existing jsonrpclib/smithy stack — **no shared mutable memory** (Chronicle-Map et al.
   rejected: no prefix/range queries, corruption-on-crash, binary-layout coupling).
2. **Prefer stable formats.** Real TASTy is the source of truth when compilation succeeds;
   best-effort TASTy is the *degraded* mode for failed compiles only. Betasty reading is
   pinned to the exact compiler version that produced it; dependency TASTy may use a shared
   reader at ≥ the max producer version (TASTy is backward compatible: reader ≥ producer).
3. **Degradation must be observable.** Every swallowed error is counted and summarized;
   every lifecycle transition is logged with counts and elapsed time; long operations report
   progress to the client. "Silently incomplete" is a bug class, not a trade-off.
4. **Dependency references stay out of scope.** `DependencyIndex` is declaration-only.
   Project reference storage will eventually go coarse-in-memory (symbol → files) +
   precise-on-demand; don't build features that assume every occurrence is always resident.
5. **Measure before optimizing.** No perf claims without a benchmark in the repo. The old
   plan's latency table is void until Phase 7 produces numbers.

---

## Phase 1 — Test harness foundation

The verification tool for everything after it. Full spec: `ai/test-infrastructure.md`.

1. `code"""…"""` marker interpolator + `CodeMarker` (no compiler dependency).
2. `TestWorkspace` — compile source strings with in-process dotc, hand back a live
   `IndexManager`/`SymbolIndex`.
3. `TestClient` — implements `SlsLanguageClient[IO]`, drives `ServerImpl` in-process.
   Requirements beyond the spec:
   - `editFile` reproduces VS Code's exact event grammar: incremental `didChange` ranges
     (delete+insert content changes), then `didSave`. The debouncer/queue/index races live
     in exactly that sequencing.
   - Deterministic waiting via `IndexLifecycle.awaitReady` and assertions via
     `slsDebugIndexOp` — no sleeps.
   - An out-of-band disk-change verb from day one (mutate files outside the client, send
     the corresponding watched-files notification) so Phase 4 has a harness waiting.
4. **Determinism property** (the workhorse assertion): after any scenario, a fresh server
   bootstrapped against the resulting workspace produces the same index as the long-lived
   server that lived through the edits. Catches stale-overwrite, invalidation, and ordering
   bugs without anticipating them individually.
5. A small number of full-stdio smoke tests (subprocess, real pipes) alongside the
   in-process tests — framing and stdout-corruption regressions only show up there.

**Done when:** one Layer-2 smoke test and one Layer-3 scenario (open → edit → save →
references) run green in CI; the determinism property exists and passes.

## Phase 2 — Index write correctness

1. **Write ordering.** All `ProjectIndex` writes (bootstrap, `onCompilationComplete`,
   `updateOpenFile`, `onFilesDeleted`) go through one sequencing point with per-file
   generation stamps; a write tagged with an older generation than the file's current one is
   dropped. Fixes: compile-finishing-after-newer-didChange clobbering, bootstrap landing
   after a fresh save and reverting it.
2. **No-op saves stop reindexing the whole target.** Empty changed-set from a *successful*
   compile means skip; today it falls through to extracting and reindexing every betasty in
   the jar. zincCli already knows `hasModified`; surface "nothing changed" explicitly.
3. **TASTy on success, betasty on failure.** zincCli derives `(outputFormat, entrySuffix)`
   per compile from the reporter (errors → `BETASTY`/`.betasty`, clean → `TASTY`/`.tasty`)
   instead of hardcoding BETASTY. Precondition: verify what the output jar actually contains
   in each scenario (list a real jar after a clean and a failed compile). The temp-dir
   extraction workaround becomes failure-path-only.
4. **Enrich `SemanticdbIndexer`.** Stop discarding `SymbolInformation`: populate parents
   (from `ClassSignature.parents` via `SymbolId.fromSemanticDb`), visibility (`access`),
   owner, overrides. Skip `localN` symbols. Precondition: confirm the PC's
   `semanticdbTextDocument` populates `signature` (dotc's `ExtractSemanticDB` does).
   This removes the fidelity downgrade where editing a file silently deletes its subtype
   edges until the next save. (The PC print-TASTy endpoint idea is rejected — the data is
   already in the semanticdb bytes.)
5. **Failed-symbol handling.** `SymbolId.tpe(Nil, Nil, "<unknown>")` collides across all
   extraction failures; either drop the symbol or give the sentinel provenance. Add
   swallowed-error counters in `SymbolCollector` (and `JavaIndexer`'s collector) with one
   summary log line per run: "indexed X symbols, skipped Y trees".

**Done when:** the determinism property passes under an edit-save-edit-quickly scenario;
a TestClient test asserts type hierarchy survives an edit without a save; cross-producer
spec extended with extension methods, exports, top-level defs.

## Phase 3 — Lifecycle robustness

1. **No eternal `Bootstrapping`.** Per-jar timeout + `IO.interruptible` (best-effort) around
   inspector runs; a global bootstrap deadline that forces `Failed`. Today one pathological
   jar wedges an uncancelable `IO.blocking` forever, `Failed` never fires (it needs an
   exception), and shutdown can hang on the stuck thread. This is interim protection until
   Phase 8 makes timeouts a `kill -9`.
2. **Re-import and retry.** `DependencyIndex.removeJar(jarPath)` (via `symbolJar`); build
   re-import (PR #86) diffs jar sets by SHA and removes/re-adds only changed jars, each as
   one atomic `Ref.update`. `Failed` becomes retryable — re-entering bootstrap is legal.
   Lifecycle gains a re-bootstrap transition; queries during reindex are served from the
   current state while the phase signals work in progress.
3. **Progress notifications.** Replace `logMessage` strings with LSP work-done progress
   tokens for bootstrap/reindex. Users must be able to tell "index rebuilding" from
   "references are broken".
4. **Queue hygiene.** Move `awaitReady` in `handleReferences` *outside*
   `computationQueue.synchronously` (today one references call during bootstrap blocks all
   LSP traffic for up to 2s). Replace `pushSync`-deferred classpath swap with an eagerly
   submitted, per-target-deduplicated queue task — same mutual exclusion with PC use,
   honest latency attribution, no redundant unzips. (The unzip itself disappears when the
   betasty-in-jar bug is fixed upstream.)
5. **Save failures surface.** A failed or timed-out `compileWithCSP` in `handleDidSave`
   currently dies silently in the supervisor; report it to the client.

**Done when:** a test double that hangs in `indexJar` produces `Failed` within the deadline
instead of eternal `Bootstrapping`; a dependency-version bump scenario converges without
restart; references during bootstrap don't block hover.

## Phase 4 — External file changes (the branch-switch test)

Everything today flows from editor events or compile output; `git checkout` produces
neither, and the index silently diverges from disk. The canonical IDE reliability test.

1. Register for `workspace/didChangeWatchedFiles` (capability negotiation; fall back to our
   own watcher only if the client doesn't support it).
2. Changed/created/deleted files → invalidate index entries → trigger CSP compile of
   affected targets → reindex from output. Batch storms (a checkout touches hundreds of
   files) behind a short debounce.
3. Add `workspace/didCreateFiles` / `didRenameFiles` ops to match the existing
   `didDeleteFiles` (carried from the old polish list — renames currently leak stale
   per-file entries).
4. **Location verification guard:** before returning index locations to the client
   (references, definition), cheaply verify the target line still contains the expected
   name; drop or re-resolve on mismatch. Insurance against *all* drift bugs, not just this
   phase's.

**Done when:** TestClient branch-switch simulation (out-of-band mutation + notification)
converges to the same index as a fresh bootstrap; renames don't leak entries.

## Phase 5 — Seams for the split (no processes yet)

1. **Query seam:** define the read interface as a trait — a superset of mtags'
   `SymbolSearch` plus references/hierarchy queries. `SymbolIndex` implements it
   in-process; the future RPC client implements the same trait. No caller may depend on
   in-process-ness.
2. **Implement `SymbolSearch` over the index** and hand it to the presentation compiler for
   completions (the `CH` → `ConcurrentHashMap` path: camelCase trie prefix query). This is
   the PC worker's future RPC surface, proven in-process first.
3. **Indexing seam:** the indexer side is already mostly stateless behind `SymbolIndexer`
   ("jar + classpath in, symbols out"); formalize it so the implementation can become a
   remote call. The write side stays owner-internal.
4. **Bloom filter decision:** the per-jar filters have no callers and the global trie
   answers `SymbolSearch` queries directly. Delete `BloomFilter`/`jarFilters` plumbing
   unless the `SymbolSearch` implementation surfaces a per-jar scan that needs them.
   (Note: a *different* bloom design — per-file identifier filters for lazy references —
   may return in Phase 7. Don't keep the current one on those grounds.)

**Done when:** PC completions consume `SymbolSearch`; grep shows no consumer of
`SymbolIndex` concrete type outside the wiring layer; bloom decision executed.

## Phase 6 — LSP features

Each feature ships with a TestClient scenario, and degrades explicitly (phase-aware
message) when the index isn't `Ready`.

1. `workspace/symbol` — trie prefix + camelCase search; cap result count; this is the
   feature that validates search quality.
2. `textDocument/implementation` + type hierarchy (prepare/supertypes/subtypes) — depends
   on Phase 2.4 (structural facts survive edits). Decide the transitive story: supertypes
   should walk transitively like subtypes do; replace the arbitrary `maxDepth = 10` with a
   visited-set bound.
3. `textDocument/rename` + `prepareRename` — **blocked on overload disambiguation.**
   Coalesced overloads are acceptable for find-references (superset) but wrong for rename.
   Promote `Member.disambig` to the erased-arg-list form (the old plan's option 3; JVM
   descriptors rejected — SemanticDB can't produce them) before shipping rename. Rename
   also requires the Phase 4 location guard (never edit a stale location).
4. References polish: strip the debug logging (`allReferenceTargets` dump per request),
   include-declaration flag, dedupe.
5. **Standard LSP test coverage** (grows alongside the features, written at the TestClient
   level so it survives the Phase 8 PC-worker split unchanged):
   - PC-backed features (completion, hover, definition, signature help, inlay hints): a
     `TestServer` mode with the real `PresentationCompilerProvider` (it's a trait —
     constructor swap) and a real-Scala-version synthetic target; request verbs
     (`complete`/`hover`/`definition`) on `TestClient`; assertions via `code"""${m1}…"""`
     markers. Opt-in fixture mode — the provider downloads the PC via coursier on first run.
   - Protocol conformance: initialize/capabilities handshake, requests against unopened
     files (must answer gracefully, not throw — `BspStateManager.get` currently throws),
     working `$/cancelRequest`, sane responses while the index is `Cold`/`Failed`. The
     stdio smoke tests from Phase 1 host the framing-level cases. These encode "no client
     behavior can break the session" — the stability contract itself.

**Done when:** each feature has a green scenario test and a documented degradation mode.

## Phase 7 — Scale and performance (measure first)

1. **Benchmarks before changes:** bytes/symbol and bytes/reference on a real large
   workspace; `Ref.update` contention under `parEvalMapUnordered` (carried open question —
   half-day measurement, drop the concern if <5% of indexing time); query latencies for the
   `SymbolSearch` hot path. These replace the old plan's unvalidated latency table.
2. **`SymbolId` interning** (global id ↔ `Int` table, tries map to int sets, symbols in one
   array) — if and only if measurements warrant. Fixes both memory (refs carry ints, not
   `List[String]`) and the hot-path cost of case-class `hashCode` recomputation on every
   map lookup. This is the single perf optimization pre-approved in design review.
3. **Lazy references** — when reference memory measures too big: in-memory coarse map
   (`SymbolId → Set[fileUri]` or per-file name bloom filters, Metals-style), precise
   locations resolved per candidate file on demand (per-file data on disk or recomputed).
   Bounds memory by file count, not occurrence count.
4. **Dependency-TASTy result cache** — deliberately deferred (200ms/jar is acceptable and
   caching is error-prone); when added, key by
   (jar SHA × resolved-classpath fingerprint × dotc version × `DepIndexCache.Version`).
   The hermetic resolution already computes the classpath to fingerprint.

**Done when:** numbers exist in-repo; an explicit memory budget and the over-budget
fallback are documented.

## Phase 8 — Process extraction

The endgame for crash/hang/version isolation. Both workers follow the zincCli pattern:
separate JVM, smithy-defined protocol over stdio JSON-RPC, supervised + respawned, killed
on deadline.

1. **Indexer worker.** Stateless "parse this jar/these tasty files → symbols" service —
   trivially killable and parallelizable since it holds nothing worth preserving. The shell
   is version-agnostic; it loads the requested `scala3-compiler` via coursier into an
   isolated classloader (the `PresentationCompilerProvider` trick). Version rule:
   project/betasty work pinned to the target's exact compiler; dependency TASTy via a
   reader ≥ max producer version (backward compat). Nightlies resolve through coursier from
   the version string BSP reports. This deletes the `stdoutGuard`, the
   `ClasspathFromClassloader` self-classpath injection, the `Throwable`-catching, and turns
   Phase 3's soft timeouts into process kills.
2. **PC worker.** Presentation compiler moves to its own JVM (safeguard against runaway
   compilation and memory leaks). It consumes the Phase 5 `SymbolSearch` seam via *reverse*
   RPC requests over its channel (same mechanism as LSP server→client requests; jsonrpclib
   channels are bidirectional). Index stays in the LSP process — single owner, single
   writer. Budget check before committing: a `SymbolSearch` round trip on a local pipe is
   ~100–300µs against a multi-ms typecheck; if real measurements ever contradict that, the
   escape hatch is immutable snapshot shipping (write-once mmap files, atomic rename,
   `DepIndexCache`-style) — never shared mutable memory.

**Done when:** a jar that hangs the inspector costs one worker kill and a log line; a PC
OOM costs a respawn and no index loss; the LSP process no longer links dotc.

---

## Explicit non-goals / deferred (with revisit signals)

- **Dependency references** — out of scope permanently unless a concrete feature demands it.
- **Project-index persistence** — revisit when bootstrap time or memory measurements
  (Phase 7) say so, not before.
- **Scala source-parse fallback for dep jars** (TASTy → source-parse → bytecode) — full
  analysis lived in `architecture-improvements.md` (git history). Revisit when telemetry
  shows users hurt by missing `.scala` locations on dep symbols; if built, it's a new
  `IndexStrategy` case, nothing else.
- **Capability-typeclass read/write enforcement** (`IndexReader[F]`/`IndexWriter[F]`) —
  revisit when the Phase 8 RPC server is the second consumer.
- **Refreshing lifecycle phase** — Phase 3.2 may force this decision; add it fully or not
  at all.
