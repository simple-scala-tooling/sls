# Architecture improvements plan

Improvements for the indexing subsystem introduced by PR #61 (Index POC) and PR #62 (Java parser).

Seven phases (0–6), ordered so each unblocks the next. Each phase is shippable on its own and has a concrete success criterion.

---

## Pre-execution notes

- **Line numbers below reference the post-PR-#62 state** (`origin/java-parser`, `b2d3a9d`/`f78667c`). PR #62 is not yet merged into `index-poc-new`. Files only present on `java-parser` (`JavaIndexer.scala`, the hermetic-CP block in `IndexManager.scala`, `depIndexCache` defaulting on `IndexManager.scala:24`) won't exist before the merge. Anchors will need refreshing if execution starts on `index-poc-new`.
- **The index module has Weaver specs, but they don't carry the weight a refactor needs.** Files exist under `sls/test/src/org/scala/abusers/sls/index/` (`BloomFilterSpec`, `PatriciaTrieSpec`, `ProjectIndexSpec`, `DependencyIndexSpec`, `SymbolIndexSpec`, `TastyIndexerSpec`, `BytecodeIndexerSpec`, `IndexManagerSpec`, `JarMavenCoordinatesSpec`, `JavaIndexerSpec`, `DepIndexCacheSpec`, `JavaFrontendSpec`), but inspection shows three structural problems: assertions are name-based not id-based (so the `Cls#foo` vs `Cls.foo` bug is invisible), `IndexManagerSpec` constructs `mgr` and never calls its orchestration methods (`indexDependencies`, `onCompilationComplete`, `indexJarSafely` fallback chain are dead), and several tests are tautologies (`syms.forall(_.origin.isInstanceOf[SymbolOrigin.ProjectTasty])` on output produced by `TastyIndexer`). Phase 0 fixes all three.
- **Estimates are rough orders, not commitments.** Phases involving supervised fibers (5) and ID semantics (1) routinely overshoot. Communicate ranges, not point estimates.

---

## Phase 0 — Test foundation

**Goal:** make the test suite carry the weight of the refactor. Today's specs exist but don't catch the bugs the rest of the plan fixes (see Pre-execution note 2). Phase 0 fixes three structural problems — name-based assertions, dead orchestration tests, tautologies — and adds the cross-producer fixture that grounds Phase 1.

**Steps:**

### 1. Audit and rewrite existing assertions

Across `TastyIndexerSpec`, `BytecodeIndexerSpec`, `JavaIndexerSpec`, `DependencyIndexSpec`, `ProjectIndexSpec`, `SymbolIndexSpec`, `IndexManagerSpec`:
- Replace `_.name == "X"` with `_.id == expectedId`. **In Phase 0 the factories don't exist yet** — `expectedId` is the current opaque-string form (`SymbolId("fixture.SimpleClass")`, `SymbolId("com/example/Widget#foo")`, etc.). These assertions will need re-anchoring in Phase 1 once `SymbolId` becomes a case class with proper factories. Not wasted work — the assertions are the right *shape*, only the literal expected values change. The cross-producer spec (step 3) stays `// pending` to absorb this churn.
- Replace `_.value.contains(...)` substring matches with exact id equality.
- Delete tautologies like `expect(syms.forall(_.origin.isInstanceOf[SymbolOrigin.ProjectTasty]))` (`TastyIndexerSpec.scala:178-182`) — they verify the type system, not behavior.
- Tighten reference checks: `expect(overrideRefs.nonEmpty)` becomes `expect(overrideRefs.exists(_.symbol == greeterGreetId))` — assert *which* symbol is the target, not just that one exists.
- **Location assertions:** middle ground — assert location is inside the file's line range and stable across runs, not exact `startLine = N, endCol = M`. Exact values are too brittle to fixture edits; `.isDefined` is too weak.

### 2. Add IndexManager orchestration tests

`IndexManagerSpec` currently constructs `mgr = IndexManager(...)` and only calls `onFilesDeleted`. The strategy selection and compile-driven update paths are entirely untested. Add:
- TASTy-bearing jar → symbols land via the TASTy path (assert on `origin` to prove the path, not on id — see entanglement note below).
- Bytecode-only jar → symbols land via the bytecode path (assert on `SymbolOrigin.DependencyClassfile`).
- `onCompilationComplete` with a synthetic `CompileOutput` → prior URI entries are *removed*, not appended; changed-file filtering honoured.
- Java-source dependency path (PR #62) → `JavaIndexer` invoked, not `BytecodeIndexer`.

**Deferred to Phase 2:** the "TASTy crash → bytecode fallback" test. Writing it before Phase 2 requires either a known-bad fixture (hard to source) or refactoring `IndexManager` for injection ahead of schedule. Phase 2 introduces `SymbolIndexer` as a trait, which makes injection of a deliberately-crashing test double free. The fallback path stays untested for Phase 1's duration — uncomfortable but bounded.

**Phase 1 entanglement:** orchestration assertions on `origin` are independent of Phase 1 and ship green from day one. Assertions that require id equality after orchestration (e.g., "compile produces the same id as TASTy indexing the same source standalone") *depend on* Phase 1's id fix. Keep the orchestration tests in step 2 origin-based; let the cross-producer fixture (step 3) be the only red baseline.

### 3. Cross-producer fixture

Add `sls/test/resources/cross-producer/`:
- `Lib.scala` — top-level class, companion object, overloaded methods, inner class, package object.
- `LibJ.java` — Java class with the same shape.
- Mill task that produces `.tasty`, `.class`, `.semanticdb`, and a sources jar.

Add `CrossProducerSpec`: for each fixture name, index via `TastyIndexer`, `BytecodeIndexer`, `SemanticdbIndexer`, `JavaIndexer`, assert id equality (or documented inequality) across producers. **Spec is red today** on `Cls.foo` vs `Cls#foo` — that's the Phase 1 exit signal. Mark `// pending until Phase 1`.

### 4. Round-trip determinism

Same input indexed twice → same `Map[SymbolId, IndexedSymbol]`. Cheapest way to catch nondeterminism in id production. **Watch out for list ordering:** `IndexedSymbol.parents: List[SymbolId]` and per-file `List[SymbolReference]` are ordered; TASTy traversal order can shift between runs. Either assert on `.toSet`-converted views, or fix ordering in the producer (sort by some stable key on emit). Pick one and document.

**Explicitly assert reference-list order** in `ProjectIndex.references: Map[SymbolId, List[SymbolReference]]`. Today's prepend-on-insert (`ProjectIndex.scala:194`) means iteration order is "most recently added first." Phase 3's `CoreState.add` rewrite could silently flip this. Without an ordering assertion here, Phase 3 has an invisible-regression path.

### Decision: orchestration bugs uncovered by step 2

Step 2's tests will likely surface latent bugs in `IndexManager`'s fallback paths. **Land the fixes inside Phase 0 as separate commits.** Splitting into a Phase 0.5 fragments the test/code pairing without buying clearer review. Keep the diff coherent: tests-that-fail commits and code-fix commits in the same PR.

**Success criterion (manual mutation check, run once before declaring Phase 0 done — not a CI test):** temporarily mutate any one producer to emit `SymbolId("WRONG")` for every id. At least one spec in *every* rewritten file must fail. If a file still passes, its rewrite was incomplete. Revert the mutation before commit.

**Estimate:** 3–4 days. Step 1 audit is the bulk (~1.5 days). Step 2 orchestration tests + any latent bug fixes (~1 day). Step 3 fixture (~0.5 day, possibly +0.5 if Mill task wiring is finicky). Step 4 round-trip (small).

---

## Phase 1 — Canonical `SymbolId` (the correctness fix)

**Goal:** every producer emits the same `SymbolId` for the same source-level symbol. The bytecode `Cls#foo` vs TASTy `Cls.foo` mismatch disappears.

**Strategy: minimal canonical form first, ADT later if query ergonomics demand it.**

Two options to consider before starting:

### Option A (recommended) — canonical case class, drop opaque-string

```scala
final case class SymbolId(
  pkg: List[String],
  owners: List[String],     // outer classes/objects, root-to-leaf
  name: String,
  member: Option[Member],   // None = type; Some = term
)
final case class Member(name: String, kind: MemberKind, disambig: Option[String])
enum MemberKind { case Method, Val, Var, Field, Constructor, Given }
```

Producers go through `SymbolId.fromTasty`, `SymbolId.fromSemanticDb`, `SymbolId.fromJvm`, `SymbolId.fromJava`. Add `def render: String` for logging / cache keys / JSON. This is enough to fix the bug.

### Option B (fallback) — keep opaque string, add a normalizer

```scala
def canonical(raw: String, source: SymbolSource): SymbolId
```

Every producer calls `canonical(...)` on the way out. Smallest call-site delta; doesn't improve query ergonomics; doesn't model overloads.

**Pick A** unless Phase 0's red test surfaces structural issues (anonymous classes, refinements, package objects, TASTy synthetics) that make the case-class form fight back.

### Disambiguator strategy — must be decided up front

Overload resolution is the load-bearing constraint here. Three concrete options:

1. **Drop `disambig` for v1**, accept that find-references on overloaded methods returns *all* overloads. Documented degradation. Cheapest; correct for the common case. ← **recommended starting point**
2. **JVM descriptor**: `(Ljava/lang/String;I)V`. Bytecode has it natively. TASTy/Java synthesize from `sym.signature`. SemanticDB's `(+N)` doesn't map to descriptors without a sibling lookup — *this option fails for SemanticDB-only refs*, which is exactly the edit-time path.
3. **Erased-arg name list**: `(String,int)`. Producers compute uniformly; SemanticDB recovers via `SymbolInformation.signature`. Heavier than (1), strictly cheaper than full JVM descriptor matching.

**Recommendation:** ship (1) in Phase 1, log overload arity so we can measure how often it bites. Promote to (3) if real users hit it. Do not ship (2) — the SemanticDB asymmetry would make edit-time references silently inconsistent with compile-time references.

### Steps

1. In `index/IndexTypes.scala`, replace opaque `SymbolId` with Option A.
2. Add `SymbolId` factory methods (`fromTasty`, `fromSemanticDb`, `fromJvm`, `fromJava`). Each takes producer-native input and returns a `SymbolId` whose `render` is stable.
3. Delete `semanticDbToFullName` and `symbolIdCandidates` (`IndexTypes.scala:13-40`). Their workaround behavior moves into the factories.
4. Update call sites: `BytecodeIndexer.scala:69, 102, 133`; `TastyIndexer.scala:336-344`; `SemanticdbIndexer.scala:29`; `JavaIndexer.scala:226`; `ServerImpl.handleReferences` (`ServerImpl.scala:386-388`).
5. Update `IndexedSymbolCodecs` to (de)serialize the case class.
6. **Bump `DepIndexCache.Version`** (`DepIndexCache.scala:78`) — the on-disk cache (PR #62) holds pre-migration `IndexedSymbol` JSON. Deserializing old entries into the new types either crashes or silently corrupts the trie. Bumping the version byte invalidates the old cache directory cleanly. One-line change; prevents a real failure mode.
7. **Re-anchor Phase 0's id assertions.** The opaque-string literals (e.g., `SymbolId("fixture.SimpleClass")`) become structured constants (e.g., `SymbolId(pkg=List("fixture"), owners=Nil, name="SimpleClass", member=None)`). Mechanical sweep; the assertion shapes don't change.
8. **`CanEqual` hygiene:** if `SymbolId` derives `CanEqual`, every nested case (`Member`, `MemberKind`) needs it too. Easy to miss; the compiler tells you.

**Constraint — don't touch `IndexManager` during Phase 1.** The crash-fallback test was deferred from Phase 0 specifically because injection isn't free until Phase 2. If Phase 1 refactors `IndexManager.indexJarSafely`, the deferred safety net becomes fiction. Phase 1 changes producer-internal id construction and call sites; it must not change orchestration.

**Success criterion:** Phase 0's `CrossProducerSpec` turns green. The spec covers at least: top-level class, companion module, inner class, package object, overloaded method (degradation case), Java class.

**Estimate:** 5–7 days. ADT + four factories + five call-site rewrites + codec migration + cache version bump + Phase 0 re-anchoring + edge-case iteration (anonymous classes, refinements, package objects, TASTy synthetics) is more work than 3–5 days credits.

**Risk:** medium. Inner classes (`Outer.Inner.method`), anonymous classes, package objects, refinements, TASTy synthetics each have shape edge cases. The type system catches *most* call-site churn but doesn't catch "this case produces a malformed id". Phase 0's fixture is what catches that.

---

## Phase 2 — `SymbolIndexer` trait + strategy ADT

**Goal:** `IndexManager.indexJarSafely` becomes 5 lines of composition.

**Steps (order matters — the trait + constructor change is the prerequisite, not an afterthought):**

1. **Make `IndexManager` constructor params trait-typed.** Today `IndexManager` takes a concrete `BytecodeIndexer` and instantiates `TastyIndexer` / `JavaIndexer` inside methods (`IndexManager.scala:51, 106, 129` and PR #62's dependency paths). Change the constructor to take `tastyIndexerFor: String => SymbolIndexer`, `javaIndexerFor: ... => SymbolIndexer`, `bytecodeIndexer: SymbolIndexer` — or pass a single factory. This is the load-bearing change; everything downstream (the strategy ADT, the crash-fallback test, future indexer swaps) depends on it.
2. Add to `index/`:
   ```scala
   final case class IndexResult(byFile: Map[SourceUri, (List[IndexedSymbol], List[SymbolReference])])
   object IndexResult { val empty: IndexResult = IndexResult(Map.empty) }

   trait SymbolIndexer {
     def indexJar(jar: AbsolutePath, classpath: List[AbsolutePath]): IO[IndexResult]
     def indexDirectory(dir: AbsolutePath, classpath: List[AbsolutePath]): IO[IndexResult]
   }
   ```
3. Adapt the four existing indexers:
   - `TastyIndexer`, `JavaIndexer` — direct fit, just normalize the return type.
   - `BytecodeIndexer` — wrap output as `IndexResult` with no per-file grouping (single `SymbolOrigin.DependencyClassfile` bucket, empty refs).
   - `SemanticdbIndexer` — keep its pure synchronous `indexDocument`. It's a different shape (pure, sync, in-memory bytes) and forcing it into `IO[IndexResult]` would be ceremony.
4. Introduce a strategy ADT for dep-jar selection:
   ```scala
   enum DepStrategy {
     case TastyHermetic(cp: List[AbsolutePath])
     case JavaSources(jar: AbsolutePath, cp: List[AbsolutePath])
     case Bytecode
   }
   def chooseStrategy(jar: AbsolutePath, projectCp: List[AbsolutePath]): IO[DepStrategy]
   def execute(jar: AbsolutePath, s: DepStrategy): IO[List[IndexedSymbol]]
   ```
5. Rewrite `IndexManager.indexJarSafely` (`IndexManager.scala:180-249` on `java-parser`) as:
   ```scala
   chooseStrategy(jar, projectCp).flatMap { s =>
     withCache(jar, s)(execute(jar, s))
       .recoverWith { case _: TastyCrash => execute(jar, DepStrategy.Bytecode) }
       .flatMap(publish(jar, _))
   }
   ```
6. The hermetic-CP comment at `IndexManager.scala:213-234` becomes the docstring on `DepStrategy.TastyHermetic`.
7. **Land the deferred crash-fallback test** (held back from Phase 0): inject a `SymbolIndexer` test double that throws on `indexJar`, assert `indexJarSafely` falls through to `DepStrategy.Bytecode` and publishes bytecode symbols.

**Success criterion:** `indexJarSafely` is ≤15 lines. Adding a new strategy (e.g. SCIP) is a new `enum` case + one `execute` arm, nothing else. The crash-fallback test from Phase 0's deferred list is green.

**Estimate:** 2 days (the crash-fallback test adds ~half a day).

---

## Phase 3 — `SymbolStore` to deduplicate `ProjectIndex` / `DependencyIndex`

**Goal:** the trie / name-index / subtypes scaffolding lives in one place; each store adds only what's unique.

**Steps:**
1. Extract:
   ```scala
   private[index] case class CoreState(
     symbols: Map[SymbolId, IndexedSymbol],
     nameTrie: PatriciaTrie[Set[SymbolId]],
     camelCaseTrie: PatriciaTrie[Set[SymbolId]],
     subtypes: Map[SymbolId, Set[SymbolId]],
   )
   private[index] object CoreState {
     def add(s: CoreState, syms: List[IndexedSymbol]): CoreState
     def remove(s: CoreState, ids: Set[SymbolId]): CoreState
   }
   ```
2. `ProjectIndex.State` = `CoreState` + `byFile` + `references` + `refsByFile`.
3. `DependencyIndex.State` = `CoreState` + `jarFilters` + `symbolJar`.
4. Replace the `var nameTrie = ...; nameTrie = nameTrie.insert(...)` accumulators (`ProjectIndex.scala:161-188`, `DependencyIndex.scala:91-113`) with `foldLeft` over `CoreState.add` — idiomatic FP, single implementation.

**Scope clarifications:**
- **`CoreState.add/remove` operates on `List[IndexedSymbol]`**, not on files or jars. File-scoped operations (`removeFileFromState`) and jar-scoped operations (`addJarToState`) stay on the wrapping types, which compute the symbol set and delegate to `CoreState`.
- **Bloom filters are not aggregate state.** `DependencyIndex.jarFilters: Map[String, BloomFilter]` holds one filter per jar, built once at `addJar` time (`DependencyIndex.scala:89-97`). `CoreState` doesn't touch Bloom filters — they stay on `DependencyIndex.State` and are managed by `addJarToState` directly. Phase 3 doesn't change Bloom-filter semantics.
- **Dependency jars have no removal path today.** `CoreState.remove` is exercised by project files only. If a future feature needs to evict a jar (e.g., reindexing on classpath change), that's a separate piece of work, not Phase 3.

**Reference-ordering risk (linked to Phase 0 step 4):** `addFileToState` prepends new refs (`ProjectIndex.scala:194`). If `CoreState.add` reverses iteration or batches additions in a different order, `references.get(id)` returns a list in a different order. Phase 0 step 4's explicit reference-list ordering assertion is what catches a regression here.

**Success criterion:** the two state-update sites are each ≤20 lines (vs ~55 today) and share no duplicated trie logic. Existing `ProjectIndexSpec` and `DependencyIndexSpec` stay green without rewrites. Phase 0's reference-list ordering assertion stays green.

**Estimate:** 1 day.

---

## Phase 4 — Seal the module boundary

**Goal:** fix the visibility leak that lets `ServerImpl` reach through `symbolIndex.project.updateFiles` and `symbolIndex.project.symbolCount`. This is a **visibility fix, not an architectural seam.** The earlier framing as "read/write separation" overstated what the change accomplishes — see "Why not a real read/write seam" below.

**Concrete leaks being fixed:**
- `ServerImpl.scala:340` — semanticdb write reaches through `symbolIndex.project.updateFiles(...)`. This is a legitimate write that doesn't have a named verb.
- `ServerImpl.scala:389-391` — `debugReferenceKeys` exposed on the production type.
- `ServerImpl.scala:441-444` — `slsDebugIndex` reaches into both stores for introspection.

**Steps:**
1. Make `ProjectIndex` and `DependencyIndex` `private[index]`. Drop the `val` modifier on `SymbolIndex`'s constructor params (`SymbolIndex.scala:8-10`) so `project` and `dependency` become private constructor params — not "remove the fields," they're still needed internally; just not exposed.
2. Add `IndexManager.updateOpenFile(uri, syms, refs)` — name the PC-driven update path explicitly. `ServerImpl.pcDiagnostics` (`ServerImpl.scala:357-361`) calls it instead of reaching through the façade.
3. Move introspection methods (`symbolCount`, `fileCount`, `jarCount`, `debugReferenceKeys`) onto `SymbolIndex` as proper queries. Rename `debugReferenceKeys` to something honest like `allReferenceTargets` while you're there.
4. `SimpleLanguageServer.scala:91-97` keeps constructing the stores and passing them to both `IndexManager` (writes) and `SymbolIndex` (reads). No "ownership" change — both consume the same private stores by reference, same as today.

**Why two types instead of one combined `Index`:** convention. `IndexManager.onCompilationComplete` and `SymbolIndex.getSymbol` read differently at call sites; two named types make intent clear. The split is *naming*, not *enforcement*.

**Why not a real read/write seam:** cats-effect can't type-system-enforce read-only on a `Ref[IO, S]` — anyone holding the `Ref` can update it. Real enforcement requires capability typeclasses (`IndexReader[F]` / `IndexWriter[F]`), which is the only place in this codebase that would do capability passing. Premature for one consumer (`ServerImpl`). Revisit if a second read-only consumer (metrics exporter, RPC server) lands.

**Success criterion:**
- `grep -r "ProjectIndex\|DependencyIndex" sls/src` outside `sls/src/org/scala/abusers/sls/index/` returns zero hits.
- `grep -r "symbolIndex.project\|symbolIndex.dependency" sls/src` returns zero hits.

**Estimate:** 0.5 day.

**Future option** (do not pursue now): if a second consumer lands that genuinely shouldn't write — e.g., out-of-process workspace-symbol server, metrics exporter — promote to a capability-typeclass design (`trait IndexReader[F]; trait IndexWriter[F]; class Index extends both`). Pay that cost only when the second consumer is concrete, not on speculation.

---

## Phase 5 — Lifecycle: a real readiness model

**Goal:** the `.start`-and-pray pattern (`ServerImpl.scala:100-103`, `:283-287`) is replaced by an explicit phase machine so query-side code can reason about freshness. The `timeoutTo(5.seconds, IO.pure(Nil))` in `SymbolIndex.scala:32` stops being load-bearing.

**Steps:**
1. Add to `index/`:
   ```scala
   enum IndexPhase { case Cold, Bootstrapping, Ready }   // no Refreshing — see below
   class IndexLifecycle private (state: SignallingRef[IO, IndexPhase]) {
     def phase: IO[IndexPhase]
     def awaitReady(timeout: FiniteDuration): IO[Either[Timeout, Unit]]
     def transition(next: IndexPhase): IO[Unit]
   }
   ```
   **`Refreshing` is intentionally absent.** A half-implemented ADT rots; either ship it fully or leave it out. The on-save compile-driven update is fast enough to not need a separate phase. Add `Refreshing` later when there's a concrete use case (e.g., post-Phase-3 BSP reimport that takes >1s).
2. `IndexManager` wraps each long-running op (`indexDependencies`, `indexExistingProjectArtifacts`, `indexJdkSources`, `onCompilationComplete`) with phase transitions and runs them under a `Supervisor[IO]` instead of bare `.start`.
3. `initialized` becomes:
   ```scala
   for {
     _       <- bspStateManager.importBuild
     targets <- bspStateManager.getAllTargets
     _       <- indexManager.bootstrap(targets)  // supervised; flips lifecycle.phase
   } yield ()
   ```
4. Query-side ops that benefit from a fresh index (find-references, workspace symbols) call `lifecycle.awaitReady(2.seconds)` — degrades gracefully on timeout instead of masking the race in `searchSymbols`.
5. `ResourceSupervisor` (already used in `ServerImpl`) acquires the supervisor so shutdown cancels it cleanly.
6. **Observability:** each `transition` emits an info-level log with the from/to phase, elapsed time, and counts (`symbolCount`, `fileCount`, `jarCount`). One line per transition. `lspClient.logMessage` for client-visible state changes (`Bootstrapping → Ready` with "indexed N files in T seconds"); logback for the rest. This is the *one* place where info-level logging earns its keep.

**Success criterion:**
- A test that starts the server, immediately fires a `references` request, and asserts either correct results OR an explicit "indexing-in-progress" response — never `Nil`-by-default.
- Restarting the server twice in a row produces consistent reference counts.
- Each phase transition produces exactly one log line with phase, duration, and counts.

**Estimate:** 2–4 days. Cats-effect supervision and signal-based readiness rarely land in two days. With `Refreshing` removed, the v1 scope is bounded: `IndexPhase` is 3 cases, `IndexLifecycle` is ~30 lines, the integration is replacing two `.start` sites with `Supervisor[IO].supervise`.

---

## Phase 6 — Polish (independent, small)

Each item is a single PR-sized chunk; pick them up between phases as time allows.

1. **`safe[A]` helper** — replace ~12 inline try/catch blocks in `TastyIndexer.scala:336-433` and `JavaIndexer`'s collector with one `inline def safe[A](op: => A): Option[A]`. Same code, ⅓ the lines.
2. **Demote storage logs to debug.** `ProjectIndex.scala:53-54, 60, 68-70, 191-193` are info-level on every compile — move to `debug`.
3. **`installStdoutGuard` as a `Resource`** — `SimpleLanguageServer.scala:40` mutates `System.out` once and never restores. Wrap as `Resource[IO, Unit]` and chain into the existing flow.
4. **Inject `DepIndexCache`** — `IndexManager.scala:24` defaults to `DepIndexCache.default`. Make it a required arg constructed in `SimpleLanguageServer`. Same for the now-mutable `coursierCache: Cache` field.
5. **Replace inlined `traverse`** in `SymbolIndex.scala:94-99` with `cats.syntax.all.*` — already imported across the codebase.
6. **Document `IndexedSymbolCodecs.nullValue`** — one-liner explaining the null-cast is a jsoniter sentinel never returned on read paths.
7. **Add `didCreate` / `didRename` LSP ops** to match the existing `workspaceDidDeleteFiles` (`ServerImpl.scala:442-445`). File rename currently leaks stale per-file index entries.
8. **Wire `CompileOutput.outputFormat`** in `csp.smithy` so `IndexManager.onCompilationComplete` doesn't peek at the jar to detect TASTy vs BeTASTy.

---

## Open questions / underspecified

These are worth resolving before or during execution, not after.

- **Overload disambiguator** (see Phase 1). Decision: ship with `disambig = None`, accept "all overloads" degradation, log arity, promote to erased-arg-list form if real users hit it.
- **`Ref[IO, State]` contention under `parEvalMapUnordered(4)`** (`IndexManager.scala:34`). The trie rebuild inside `state.update` runs on every dep-jar add; with 4-way concurrency this becomes a contention point. **This is measurable now — don't carry it as an open question.** Half-day task: add timing around `state.update` in a real project (`sls` itself), check whether CAS retries happen and how long the trie rebuild dominates. If contention is invisible (<5% of total indexing time), drop this concern entirely. If it shows up, batch updates per-jar into a single `update` call (already mostly the case) and consider an `AtomicCell`. Schedule before Phase 3 so the data informs the refactor.
- **Dependency-source references are not tracked** (only project references are stored in `ProjectIndex.references`). The plan **preserves this non-goal**: dep refs are 100× the data and the use case (find-references inside the JDK) is weak. Stated explicitly here so the phases don't accidentally widen scope.
- **`CanEqual` hygiene for the new `SymbolId` hierarchy** — if `SymbolId` derives `CanEqual`, so must `Member`, `MemberKind`, and any other sealed nested type. Compiler enforces, but worth mentioning in the Phase 1 task breakdown.
- **TASTy edge cases** (Phase 1 risk): inner classes, anonymous classes, package objects, refinements, synthetics. Phase 0's fixture must include at least the first three.

---

## Sequencing notes

- **Ship 0 first.** Phase 0 is the red baseline. Without it, Phase 1's success is unverifiable.
- **Phase 1 is the correctness fix.** Land it second. Everything after is structural and can land independently.
- **2 and 3 are independent** — pick whichever feels better to start. 2 has more user-visible effects (cleaner stack traces), 3 has more code-volume reduction.
- **4 is independent of 2 and 3** — it's a visibility fix, not an architectural change. Ship whenever convenient. (The earlier framing implied 4 depended on the structural cleanups; it doesn't.)
- **5 is independent of 1-4** — could ship at any point, but most valuable after 4 (when the read API is unambiguous).
- **Phase 6 items are drop-in.**

## What not to do

- **Don't introduce a persistence layer yet.** The on-disk `DepIndexCache` covers cold-start cost; the in-memory index is small (project size). Persistence is the right call when index sizes hit ~hundreds of MB, not before.
- **Don't unify `SemanticdbIndexer` into the `SymbolIndexer` trait.** It's pure, synchronous, and operates on already-loaded bytes from the PC — forcing it into `IO[IndexResult]` would be ceremony.
- **Don't merge `JavaFrontendDriver` and `TastyInspectorDriver` into a common abstraction yet.** The structural parallel is tempting but each has compiler-specific phase setup. Wait for a third driver before factoring.
- **Don't track dependency-source references** (see Open Questions). Out of scope.
- **Don't promote `SymbolId` to a full ADT with overload-precise disambig in Phase 1.** Phase 1 fixes the bug; Phase 1.5 (not scheduled) revisits if needed.

---

## Future work — test infrastructure

**Pre-compiled fixture JARs are a stopgap, not a strategy.**

`CrossProducerSpec` and `TastyIndexerSpec` currently rely on Mill modules (`crossProducerFixture`, `tastyIndexerFixture`) that pre-compile fixtures and inject the JAR into test resources. This avoids calling dotc at test time but creates a gap: the tests never exercise a real, end-to-end Scala compilation — the fixture is frozen at the moment the Mill task ran.

What we actually need for the long term is a test infrastructure that:

- Spins up a real compilation (BSP, Zinc, or direct scalac) against a live source tree.
- Feeds the compile output (TASTy, class files, SemanticDB) into the indexers and lifecycle machinery.
- Makes it easy to write a new fixture as a plain `.scala` file and assert on the index state, without any boilerplate around compilation.
- Is fast enough to run in CI — probably via a shared, cached Zinc analysis store across suites.

Concretely: something like a `TestWorkspace` helper that takes a `Map[String, String]` (filename → source), compiles it incrementally, and hands back a fully-initialised `IndexManager` / `SymbolIndex` pair ready to query. The `IndexManagerSpec` orchestration tests (deferred to Phase 2) are the first natural consumer.

**Migration signal:** when a test needs to assert something about compilation-driven updates (`onCompilationComplete`, the TASTy-crash-to-bytecode fallback, incremental re-index on edit) and the pre-compiled JAR can't express it — that's when to build the real infra. Don't build it speculatively.

## Estimate

Rough order, single engineer. Communicate as ranges, not commitments.

- Phase 0: 3–4 days (audit + orchestration tests + fixture + round-trip)
- Phase 1: 5–7 days (ADT + factories + call sites + codec + cache version + Phase 0 re-anchoring + edge cases)
- Phase 2: 2 days (trait-typed constructor + strategy ADT + crash-fallback test)
- Phase 3: 1 day (+ half-day Ref-contention measurement beforehand)
- Phase 4: 0.5 day (visibility fix)
- Phase 5: 2–4 days
- Phase 6: 1–2 days total, opportunistic

**~3–4 weeks end-to-end.** Phases 1 and 5 are the two most likely to overshoot — communicate the upper bound. Each phase shippable in isolation, with green tests at every step.
