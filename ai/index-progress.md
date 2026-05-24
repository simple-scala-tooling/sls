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

## Next
- **Phase 2** `SymbolIndexer` trait + strategy ADT — make `IndexManager.indexJarSafely` 5 lines of composition; land the deferred TASTy-crash → bytecode fallback test
- **5.1–5.6** LSP feature handlers (references, workspace/symbol, rename, type hierarchy, didDeleteFiles)
