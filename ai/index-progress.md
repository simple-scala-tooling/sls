# Index Implementation Progress

## Completed
- **0.1** IndexTypes — core data model types
- **0.2** PatriciaTrie — immutable compressed radix tree with prefix search
- **0.3** BloomFilter — per-JAR pre-filtering with double hashing
- **0.4** CamelCaseUtils — CamelCase skeleton extraction for workspace symbol search

- **1.1** ProjectIndex — mutable project index with file-level invalidation
- **1.2** DependencyIndex — immutable dependency index with per-JAR Bloom filters
- **1.3** SymbolIndex — unified facade over both index tiers

- **2.1** TastyIndexer — TASTy Inspector-based indexer with tree walking

- **2.2** BytecodeIndexer — ASM-based bytecode indexer for Scala 2/Java JARs

- **3.1** CSP CompileOutput — added changedFiles field (Smithy + zinc Server extraction)

- **3.2** IndexManager — index lifecycle manager (dependency/project indexing, incremental updates, file deletion)

- **3.3** IndexManager wired into server lifecycle (Smithy ops, initialized background indexing, didSave index update, stub handlers)

## Architecture-improvements plan
- **Phase 0** Test foundation — name-based → id-based assertions, orchestration tests, cross-producer fixture, round-trip determinism (PR #63)

- **Phase 1** Canonical `SymbolId` — case-class with `pkg/owners/name/member`. Producers go through `fromTasty/fromJvm/fromSemanticDb/fromJava` factories; `Cls.foo` vs `Cls#foo` drift removed. `CrossProducerSpec` flipped from `ignore(...)` to green across all 6 cases (top-level class, companion object, overloaded method, inner class, Java class, Java overloaded method). `DepIndexCache.Version` bumped to 3.

- **Phase 2** `SymbolIndexer` trait + `IndexStrategy` ADT — adapter layer flattens each producer's native return type (TastyIndexer's `Map`, BytecodeIndexer's raw list, JavaIndexer's `Map`) into a uniform `IO[List[IndexedSymbol]]`. `IndexManager.indexJarSafely` is now `chooseStrategy → runStrategy → addJar` composition (5 lines). Deferred TASTy-crash → bytecode fallback test landed — a JAR with corrupted `.tasty` + valid `.class` entries now exercises the error-recovery path end-to-end.

- **Phase 3** `CoreState` deduplication — extracted `private[index] CoreState(symbols, nameTrie, camelCaseTrie, subtypes)` with `add(List[IndexedSymbol])` / `remove(Set[SymbolId])`. `ProjectIndex.State` wraps `core` + `byFile` + `references` + `refsByFile`; `DependencyIndex.State` wraps `core` + `jarFilters` + `symbolJar`. The `var nameTrie = ...; nameTrie = nameTrie.insert(...)` accumulators collapsed to `foldLeft` over `CoreState.add`. Wrapping state-update sites are now ~11–22 lines each (down from ~41–55). All existing specs stay green.

- **Phase 4** Seal the module boundary — `ProjectIndex` and `DependencyIndex` are now `private[index]`; `SymbolIndex`'s `project`/`dependency` fields are `private[index] val`. `IndexManager` takes a `SymbolIndex` instead of the two stores directly, and owns the new `updateOpenFile(uri, syms, refs)` verb that `ServerImpl.pcDiagnostics` calls (replaces the `symbolIndex.project.updateFiles(...)` reach-through). Introspection (`projectSymbolCount`, `dependencySymbolCount`, `fileCount`, `jarCount`, `allReferenceTargets` — renamed from `debugReferenceKeys`) lives on `SymbolIndex`. `SimpleLanguageServer` constructs the façade via `SymbolIndex.empty`. Both grep success criteria are zero hits.

- **Phase 5** Lifecycle readiness model — new `IndexLifecycle` (`SignallingRef[IO, IndexPhase]` with `Cold/Bootstrapping/Ready/Failed`) and a `cats.effect.std.Supervisor[IO]` constructed in `SimpleLanguageServer`. `IndexManager.bootstrap(targets)` uses `lifecycle.tryStartBootstrap` for an atomic Cold → Bootstrapping transition (idempotent — re-calls log a skip), supervises the JDK/dependencies/project-artifacts indexers in parallel, logs an elapsed-time + counts summary, and notifies the LSP client via a `notifyProgress` callback. On error the lifecycle flips to `Failed` so `awaitReady` short-circuits instead of waiting on a fiber that will never complete. Both `.start` fire-and-forget sites in `ServerImpl` (`initialized` and `handleDidSave`) are gone — the supervisor owns those fibers. `handleReferences` calls `lifecycle.awaitReady(2.seconds)` outside the cancel-token scope and surfaces a phase-aware `window/logMessage` on timeout instead of silently returning `Nil`. The `timeoutTo(5.seconds, IO.pure(Nil))` masking workaround in `SymbolIndex.searchSymbols` is deleted. `IndexLifecycleSpec` covers initial Cold, transitions, immediate-Ready awaitReady, timeout-on-Cold, async unblocking to Ready and Failed, Failed short-circuit, and `tryStartBootstrap` win-once semantics.

## Next
- **5.1–5.6** LSP feature handlers (references, workspace/symbol, rename, type hierarchy, didDeleteFiles)
