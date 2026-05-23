# SLS Indexing System — Behavioral Specification & Implementation Plan

## Context

SLS currently relies entirely on the Presentation Compiler for per-file IDE features (completion, hover, definition, signature help, inlay hints, diagnostics). Cross-module features — **find references**, **workspace symbol search**, **rename**, **type hierarchy**, and **implementations** — require a global index of all symbols and their relationships across the entire project and its dependencies. This plan decomposes the indexing system into self-contained modules ordered by dependency, each fully specified for implementation by AI agents.

### Architecture Summary

- **Two-tier index**: immutable `DependencyIndex` (from classpath JARs) + mutable `ProjectIndex` (from TASTy produced by zinc-cli)
- **Primary search**: Patricia Trie for O(k) prefix/CamelCase name search
- **Incremental updates**: file-level granularity using `changedFiles` from extended CSP `CompileOutput`
- **Background enrichment**: lazy source JAR resolution via Coursier for dependency navigation

### Critical Existing Files

- `sls/src/org/scala/abusers/sls/ServerImpl.scala` — LSP handlers, `handleDidSave` triggers compilation
- `sls/src/org/scala/abusers/sls/SimpleLanguageServer.scala` — component wiring in `server()` Resource
- `sls/src/org/scala/abusers/sls/BspStateManager.scala` — `ScalaBuildTargetInformation` (classpath, classesDir, sources), `compileWithCSP()`, `importBuild()`
- `zincCli/src/org/scala/abusers/zincCli/Server.scala` — `ZincCliServer.compile()`, has `compilationResult.analysis()` and `compilationResult.hasModified`
- `zincSmithy/smithy/csp.smithy` — `CompileOutput { outputJar: String }`
- `slsSmithy/smithy/lsp.smithy` — current LSP operations
- `build.mill` — `sls` module deps (cats-effect 3.6.3, fs2-io 3.13.0-M2, coursier interface 1.0.28, weaver-cats 0.8.4); ASM 9.6 in `zincCli` but not yet in `sls`

---

## Phase 0 — Foundation: Pure Data Structures

No external dependencies. All modules in this phase are pure Scala with no IO, cats-effect, or fs2.

---

**Task**: Define the core index data model types
**File**: `sls/src/org/scala/abusers/sls/index/IndexTypes.scala`
**Description**: Create foundational types in package `org.scala.abusers.sls.index`. Includes `SymbolId` (opaque type over `String` for fully-qualified symbol identifiers like `"scala.collection.immutable.List#map"`), `IndexedSymbol` (complete symbol representation with id, name, kind, location, owner, visibility, signature, parents, overrides, origin), `SymbolReference` (reference to a symbol at a location with reference kind), `Location` (URI + 0-indexed line/column ranges), and supporting enums.
**Purpose**: Lingua franca types consumed and produced by every other module. Indexers output `IndexedSymbol`/`SymbolReference`; indexes store them; LSP handlers query them. Pure types with no IO dependency enable trivial testing and universal use.
**Behavior**:
- `SymbolId` wraps `String` as an opaque type. Constructed via `SymbolId("scala.collection.immutable.List#map")`, accessed via `.value: String`. Equality is string equality.
- `IndexedSymbol` is a case class: `id: SymbolId`, `name: String` (simple name, e.g. `"map"`), `kind: SymbolKind`, `location: Option[Location]` (None for unresolved deps), `sourceFile: Option[URI]` (for file-level invalidation), `owner: Option[SymbolId]`, `visibility: Visibility`, `signature: String` (human-readable), `parents: List[SymbolId]`, `overrides: List[SymbolId]`, `typeParams: List[String]`, `origin: SymbolOrigin`.
- `SymbolReference` is a case class: `symbol: SymbolId`, `location: Location`, `referenceKind: ReferenceKind`.
- `Location` is a case class: `uri: URI`, `startLine: Int`, `startCol: Int`, `endLine: Int`, `endCol: Int`. Lines and columns are 0-indexed (LSP convention).
- `SymbolKind` enum: `Class, Trait, Object, Enum, Method, Val, Var, TypeAlias, TypeParam, Package, Constructor, Field, EnumCase, Given`.
- `Visibility` enum: `Public, Protected, Private, PackagePrivate`.
- `ReferenceKind` enum: `Call, TypeRef, Import, Override, Extends, Annotation`.
- `SymbolOrigin` enum: `ProjectTasty(buildTarget: String, sourceFile: URI)`, `DependencyClassfile(jarPath: String)`.
**Requirements**:
- All types are pure data — no IO, no cats-effect, no fs2
- `SymbolId` must be an opaque type for zero-overhead wrapping
- `IndexedSymbol.name` stores the simple name only; full name lives in `id`
- Location uses 0-indexed lines and columns to match LSP protocol
- All case classes derive standard `equals`/`hashCode`/`toString`
**Testing criteria**:
- `SymbolId` round-trips through `.value`
- `SymbolId` equality: same string = same symbol
- `IndexedSymbol` can be created with all fields populated and with `location = None`
- Each enum variant can be matched exhaustively in a pattern match
- `SymbolOrigin.ProjectTasty` stores buildTarget and sourceFile correctly
- `SymbolOrigin.DependencyClassfile` stores jarPath correctly

---

**Task**: Implement a Patricia Trie (compressed radix tree) for O(k) prefix search
**File**: `sls/src/org/scala/abusers/sls/index/PatriciaTrie.scala`
**Description**: Pure, immutable Patricia Trie data structure keyed on `String` with values of type `V`. A Patricia Trie compresses chains of single-child nodes into a single edge with a multi-character label, making it memory-efficient when keys share prefixes (which symbol names heavily do — e.g. all `scala.collection.*`). Supports insert, remove, exact lookup, and prefix enumeration.
**Purpose**: Core data structure powering workspace symbol search and name-based lookups. When a user types `"Li"` in workspace symbol search, we need all symbols whose lowercased simple name starts with `"li"`. A Patricia Trie answers this in O(k) where k = query length, regardless of index size.
**Behavior**:
- `PatriciaTrie[V]` is persistent (immutable). All mutation operations return a new trie.
- `insert(key: String, value: V): PatriciaTrie[V]` — adds or replaces value at key.
- `remove(key: String): PatriciaTrie[V]` — removes key if exists; returns same trie if not found.
- `get(key: String): Option[V]` — exact lookup, O(k).
- `prefixSearch(prefix: String): List[(String, V)]` — all entries whose keys start with prefix, O(k + m) where m = match count.
- `update(key: String, f: V => V): PatriciaTrie[V]` — applies f to value at key if it exists.
- `isEmpty: Boolean`, `size: Int` — basic queries.
- Internal: each node has `Option[V]` and `Map[Char, (String, TrieNode[V])]` mapping first character of edge label to (full-label, child). Edge compression merges single-child chains.
- Empty trie via `PatriciaTrie.empty[V]`.
**Requirements**:
- Pure, immutable — no mutation, no IO
- O(k) for insert, remove, get where k = key length
- O(k + m) for prefix search where m = number of results
- Edge compression (Patricia property): chains of single-child nodes compressed
- Must handle empty string keys
- Must handle keys that are prefixes of other keys (`"foo"` and `"foobar"` both stored)
- Thread-safe by immutability
- `prefixSearch("")` returns all entries
**Testing criteria**:
- Insert and retrieve a single key-value pair
- Insert multiple keys with shared prefix ("abc", "abd", "xyz"); verify all retrievable
- Prefix search "ab" returns "abc" and "abd" but not "xyz"
- Prefix search "" returns all entries
- Prefix search for non-existent prefix returns empty
- Remove existing key; verify gone but siblings remain
- Remove non-existent key returns unchanged trie
- Insert same key twice replaces value
- Edge case: insert empty string key
- Edge case: keys that are prefixes of each other ("a", "ab", "abc") — all stored and retrievable
- Size tracks correctly through insert and remove
- Large-scale: insert 10000 entries, verify prefix search is sub-linear
- Insert + remove of same single key yields equivalent-to-empty trie

---

**Task**: Implement a Bloom filter for fast per-JAR pre-filtering
**File**: `sls/src/org/scala/abusers/sls/index/BloomFilter.scala`
**Description**: Bloom filter using fixed-size bit array and k hash functions (via double hashing). Each dependency JAR gets its own filter. Workspace symbol queries check the filter before scanning a JAR's symbols — "definitely not present" skips the JAR entirely.
**Purpose**: With hundreds of classpath JARs, workspace symbol queries would otherwise scan every JAR's trie entries. Bloom filters provide O(k) "definitely not / maybe" pre-filtering at ~1KB per JAR.
**Behavior**:
- `BloomFilter(expectedElements: Int, falsePositiveRate: Double)` — creates optimally-sized filter.
- `add(element: String): BloomFilter` — returns new filter with element added (double hashing: `h(i) = h1 + i * h2` for i=0..k-1).
- `mightContain(element: String): Boolean` — true if maybe present, false if definitely not. Zero false negatives.
- Internal: `Array[Long]` bit set, k hash functions, m bits. Bit array sized: `m = -n * ln(p) / (ln(2))^2`. Hash functions: `k = (m/n) * ln(2)`.
**Requirements**:
- Immutable (all operations return new instances)
- No IO dependency
- Zero false negatives guaranteed
- Configurable false positive rate at construction
- ~1KB per JAR (500 symbols, 1% FP)
**Can be crude initially**: First implementation can use `Array[Boolean]` instead of bit-packed `Array[Long]`. Hashing can use `String.hashCode` with salt before switching to MurmurHash3.
**Testing criteria**:
- Add element, `mightContain` returns true
- `mightContain` for never-added element returns false (statistically)
- Add 1000 elements, verify FP rate within 2x of configured rate
- Empty filter returns false for all queries
- Same element added twice doesn't change behavior
- Edge case: empty string added and queried
- Union of two filters contains elements from both

---

**Task**: Implement CamelCase character extraction utility
**File**: `sls/src/org/scala/abusers/sls/index/CamelCaseUtils.scala`
**Description**: Utility for extracting the "CamelCase skeleton" from symbol names, used to build a secondary Patricia Trie for CamelCase matching. Example: `AbstractListType` → `"alt"`, `ListBuffer` → `"lb"`, `IOException` → `"ioe"`.
**Purpose**: LSP workspace symbol search supports CamelCase matching — typing `"ALT"` should find `AbstractListType`. The CamelCase trie is a secondary index alongside the primary name trie.
**Behavior**:
- `extractCamelCase(name: String): String` — returns lowercase CamelCase skeleton. Consecutive uppercase chars (like `IO` in `IOException`) treated as one word except last char before a lowercase.
- `isCamelCaseQuery(query: String): Boolean` — true if query contains uppercase characters.
**Requirements**:
- Pure function, no side effects
- Handle: all-lowercase, all-uppercase, single char, names starting with lowercase
- Consecutive uppercase treated as one word (split before last uppercase before a lowercase)
**Testing criteria**:
- `extractCamelCase("AbstractListType")` == `"alt"`
- `extractCamelCase("ListBuffer")` == `"lb"`
- `extractCamelCase("map")` == `"m"`
- `extractCamelCase("IOException")` == `"ioe"`
- `extractCamelCase("HTMLParser")` == `"hp"`
- `extractCamelCase("x")` == `"x"`
- `extractCamelCase("XML")` == `"x"` (single word, all caps)
- `extractCamelCase("")` == `""`
- `isCamelCaseQuery("ALT")` == true
- `isCamelCaseQuery("list")` == false

---

## Phase 1 — Index Core: In-Memory Stores

Depends on Phase 0. Uses cats-effect `Ref[IO, _]` for thread-safe state.

---

**Task**: Implement the mutable project index with file-level invalidation
**File**: `sls/src/org/scala/abusers/sls/index/ProjectIndex.scala`
**Description**: Tier 2 index for project source symbols and references. Backed by `Ref[IO, ProjectIndexState]`. State contains: `Map[SymbolId, IndexedSymbol]`, primary `PatriciaTrie[Set[SymbolId]]` (lowercased simple names), secondary CamelCase `PatriciaTrie[Set[SymbolId]]`, `Map[URI, Set[SymbolId]]` (file→symbols for invalidation), `Map[SymbolId, Array[SymbolReference]]` (reference storage), `Map[URI, Array[SymbolReference]]` (file→refs for invalidation), `Map[SymbolId, Set[SymbolId]]` (inverted parent→children subtypes).
**Purpose**: The mutable half of the two-tier system. Must support efficient file-level invalidation: when a file is recompiled or deleted, all its symbols and references are removed and replaced. This is the hot path for incremental updates after every save.
**Behavior**:
- Queries (all `IO[...]`): `getSymbol(id)`, `getSymbolsByName(name)`, `searchSymbols(query)` (prefix + CamelCase), `getSymbolsInFile(uri)`, `getReferences(id)`, `getReferencesInFile(uri)`, `getSubtypes(id)`, `getSupertypes(id)`.
- Mutations (all `IO[Unit]`): `updateFiles(files: Map[URI, (List[IndexedSymbol], List[SymbolReference])])` — atomic file-level update (remove old, insert new per URI), `removeFiles(uris: Set[URI])` — remove all symbols/refs for given files.
- `updateFiles` state transition per URI: lookup `byFile(uri)` for old symbol IDs → remove from symbols map, both tries, subtypes map → remove old refs from references + refsByFile → insert new symbols into all maps + tries + subtypes → insert new refs.
**Requirements**:
- Thread-safe via `Ref[IO, ProjectIndexState]` (atomic updates)
- File-level invalidation must be correct: removing a file removes exactly its symbols and references
- `searchSymbols` supports both prefix and CamelCase matching
- `updateFiles` is atomic per call (all files updated in one `Ref.update`)
- Subtypes map maintained correctly: adding symbol with `parents = [A, B]` → `subtypes(A) += symbolId`, `subtypes(B) += symbolId`; removal reverses this
**Testing criteria**:
- Insert symbols for a file, query back by name, ID, file
- `removeFiles` clears exactly that file's symbols
- Insert for files A and B, remove A — B's symbols intact
- `updateFiles` with same URI replaces old symbols (recompilation simulation)
- Prefix search and CamelCase search find matching symbols
- `getReferences` returns references across multiple files
- `getSubtypes` correct after insertion; clean after removal
- Empty index returns empty for all queries
- Concurrent: two fibers calling `updateFiles` don't corrupt state

---

**Task**: Implement the immutable dependency index with per-JAR Bloom filters
**File**: `sls/src/org/scala/abusers/sls/index/DependencyIndex.scala`
**Description**: Tier 1 index for classpath dependency JARs. Populated once during initialization; immutable during normal operation. No reference tracking (declarations only). Per-JAR Bloom filters for workspace symbol pre-filtering. Backed by `Ref[IO, DependencyIndexState]` to allow initial population and background source location updates.
**Purpose**: Dependencies contain orders of magnitude more symbols than project sources. Must be memory-efficient with fast prefix search. Bloom filters per JAR skip non-matching JARs during workspace symbol queries.
**Behavior**:
- Queries: `getSymbol(id)`, `getSymbolsByName(name)`, `searchSymbols(query)`, `getSubtypes(id)`, `getSupertypes(id)`.
- Population (during init): `addJar(jarPath, symbols)` — add symbols + build Bloom filter for the JAR, `addFromTasty(jarPath, symbols)` — same for Scala 3 TASTy deps.
- Background updates: `updateLocations(updates: Map[SymbolId, Location])` — atomically update locations for symbols resolved from source JARs.
- `searchSymbols` checks Bloom filters per JAR before scanning trie entries.
**Requirements**:
- Thread-safe via `Ref[IO, DependencyIndexState]`
- Population builds per-JAR Bloom filter simultaneously
- `updateLocations` efficient for batch updates (1000 symbols at once)
- No reference tracking (declarations only)
- Consider string interning for repeated package prefixes
**Testing criteria**:
- Add symbols for a JAR, query back by name, ID
- Workspace symbol search across multiple JARs
- Bloom filter pre-filtering: JAR-A has "Foo", query "Bar" → filter says not present
- `updateLocations` changes location from None to Some(location)
- `getSubtypes` works across JARs
- Empty index returns empty

---

**Task**: Implement the unified facade over both index tiers
**File**: `sls/src/org/scala/abusers/sls/index/SymbolIndex.scala`
**Description**: `SymbolIndex` merges query results from `ProjectIndex` and `DependencyIndex` into a single interface. All LSP handlers interact with `SymbolIndex`. Handles result deduplication and priority (project overrides dependency for same ID).
**Purpose**: Callers should not need to know about the two-tier architecture. Provides unified query API plus higher-level queries like `getImplementations` (transitive subtypes).
**Behavior**:
- `getSymbol(id)` — check project first, fall back to dependency.
- `getSymbolsByName(name)` — merge results, project first.
- `searchSymbols(query)` — merge from both tiers' tries.
- `getReferences(id)` — project only (deps have no references).
- `getSubtypes(id)` / `getSupertypes(id)` — merge from both tiers.
- `getImplementations(id)` — transitive closure of subtypes, filtered to concrete classes.
- `resolveSymbolAtPosition(uri, line, col)` — find symbol at position in a file by scanning project index symbols for that file, finding innermost containing position.
**Requirements**:
- Project symbols take priority over dependency symbols with same ID
- Results ordered: project first, then dependencies
- `getImplementations` computes transitive closure with depth limit
- `resolveSymbolAtPosition` handles nested scopes
**Can be crude initially**: `resolveSymbolAtPosition` can start as simple position-containment scan. Transitive type hierarchy can initially be one level deep.
**Testing criteria**:
- Symbol in project only → found
- Symbol in dependency only → found
- Symbol in both → project version returned
- `searchSymbols` returns from both tiers
- `getReferences` returns only project references
- `getImplementations` follows transitive subtype chain
- `resolveSymbolAtPosition` finds correct symbol at cursor

---

## Phase 2 — Indexers: Reading Symbols from Files

Depends on Phase 0 (IndexTypes). Can be developed in parallel (2.1 and 2.2 are independent).

---

**Task**: Implement the TASTy Inspector-based indexer for Scala 3 sources
**File**: `sls/src/org/scala/abusers/sls/index/TastyIndexer.scala`
**Description**: Uses `scala.tasty.inspector.TastyInspector` API to walk TASTy trees and extract `IndexedSymbol` for definitions and `SymbolReference` for references. Results keyed by source file URI for direct use with `ProjectIndex.updateFiles()`. This is the most complex indexer — must handle the full range of Scala 3 constructs including best-effort TASTy.
**Purpose**: Scala 3 project sources compiled with `-Ybest-effort -Ywith-best-effort-tasty` by zinc-cli produce .tasty files with full typed trees and positions. These are the primary source for extracting symbols and references.
**Behavior**:

Entry points:
- `indexFiles(tastyFiles: List[os.Path], classpath: List[os.Path], buildTarget: String): IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]]` — specific files for incremental updates.
- `indexDirectory(classesDir: os.Path, classpath: List[os.Path], buildTarget: String): IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]]` — all .tasty in dir for initial population.
- `indexJar(jarPath: os.Path, classpath: List[os.Path]): IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]]` — all .tasty in JAR for Scala 3 deps.

Tree walking strategy (implements `Inspector` trait):
1. **`ClassDef`**: Extract SymbolId from `symbol.fullName`, determine kind via flags (`Trait` / `Module`=Object / `Enum` / `Enum+Case`=EnumCase / else Class). Extract visibility from `Private`/`Protected` flags + `privateWithin`. Extract parents from parent trees' `tpe.typeSymbol.fullName`. Extract location from `symbol.pos`. Skip if `Synthetic`/`Artifact`/anonymous. Recurse into body.
2. **`DefDef`**: Kind = `Method` or `Constructor` (if `isClassConstructor`). Extract overrides from `allOverriddenSymbols`. Skip `Synthetic` or `$`-prefixed. Recurse into params and body for references.
3. **`ValDef`**: Kind = `Val`, `Var` (if `Mutable`), or `Given` (if `Given` flag). Skip `Synthetic`/`CaseAccessor`. Recurse into type and rhs.
4. **`TypeDef`** (non-ClassDef): Kind = `TypeAlias` or `TypeParam`.
5. **Reference extraction**: `Ident`/`Select` → `ReferenceKind.Call`. `TypeIdent`/`TypeSelect` → `TypeRef`. `Import` selectors → `Import`. Parent types in ClassDef → `Extends`. Override relationships → `Override`.

Best-effort TASTy error handling:
- Wrap entire tree processing per `Tasty` in try/catch
- Individual symbol extraction wrapped in `Try { ... }.toOption`
- Position extraction may fail for compiler-generated trees → use parent position
- Type signature extraction may fail → default to `"<unknown>"`

**Requirements**:
- Must run with compilation classpath for type resolution (pass to `TastyInspector.inspectAllTastyFiles`)
- Wrapped in `IO.blocking` (TASTy Inspector runs synchronously, loads class files)
- Deterministic output for same input
- Skip synthetic/compiler-generated symbols (`$anon`, `$evidence`, etc.)
- Handle best-effort TASTy without crashing — partial results > no results
- Results keyed by source URI for file-level invalidation
- Handle multi-class files (multiple top-level defs → same source URI)
- `SymbolId` format must be consistent between TastyIndexer and BytecodeIndexer
**Can be crude initially**: Signature extraction can start as `symbol.fullName` + simple stringification. Override resolution limited to direct overrides. Reference extraction initially skips annotations and imports, focusing on `Ident`/`Select`/`TypeIdent`/`TypeSelect`.
**Testing criteria**:
- Simple class with method → both symbols extracted
- Trait with abstract method → Trait kind, method marked
- Case class → constructor, fields, companion
- Enum with cases → Enum and EnumCase kinds
- Sealed trait children → parent/child relationships
- Class extending trait → parents list correct
- Method override → overrides list populated
- Method call → Call reference generated
- Type annotation → TypeRef reference
- Best-effort TASTy (compilation errors) → partial symbols extracted
- Multiple top-level defs → all keyed to same source URI
- Synthetic symbols not indexed
- Visibility (private/protected/public) correctly determined

---

**Task**: Implement the ASM-based bytecode indexer for Scala 2 and Java dependency JARs
**File**: `sls/src/org/scala/abusers/sls/index/BytecodeIndexer.scala`
**Build change**: Add `mvn"org.ow2.asm:asm:9.6"` to `sls` module `mvnDeps` in `build.mill`
**Description**: Uses `org.ow2.asm` ClassReader/ClassVisitor to read .class files from dependency JARs and extract `IndexedSymbol` records. Declarations only (no references from bytecode). Symbols have `location = None` initially; resolved later by `SourceJarResolver`.
**Purpose**: Index Scala 2 and Java dependency JARs for workspace symbol, type hierarchy, and implementations.
**Behavior**:
- `indexJar(jarPath: os.Path): IO[List[IndexedSymbol]]` — opens JAR as ZipInputStream, runs ASM ClassReader on each .class entry.
- ClassVisitor extracts: class name (JVM internal → dotted), superclass, interfaces (→ parents), access flags (→ kind + visibility), source file attribute.
- Methods via `visitMethod`: name, descriptor → signature, access flags. Skip bridge/synthetic/clinit.
- Fields via `visitField`: name, descriptor, access flags.
- Scala detection: `$` suffix → Object, `package$` → package object. `ACC_INTERFACE` → Trait, `ACC_ENUM` → Enum.
- JVM name conversion: `com/example/Foo` → `com.example.Foo`, methods → `com.example.Foo#methodName`.
- Uses `ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES` for performance.
**Requirements**:
- Read JARs via `java.util.zip.ZipInputStream` (no extraction to disk)
- Handle malformed .class files gracefully (skip with warning)
- All symbols have `location = None`, `origin = DependencyClassfile(jarPath)`
- Wrapped in `IO.blocking` (disk I/O)
- SymbolId format consistent with TastyIndexer
**Can be crude initially**: Generic signature parsing returns raw JVM descriptor as fallback. ScalaSig parsing skipped entirely. Object detection via simple `$` suffix heuristic.
**Testing criteria**:
- JAR with Java class → class and method symbols extracted
- JAR with Scala 2 case class → class, fields, companion
- Interface → Trait kind
- Enum → Enum kind
- Access flags → correct Visibility
- Synthetic methods filtered
- Source file attribute captured
- Inner classes attributed to owners
- Object companion detected via `$` suffix
- Empty JAR → empty results
- JAR with non-class resources → no crash

---

## Phase 3 — Protocol & Integration

Depends on Phases 1 and 2.

---

**Task**: Extend CSP CompileOutput to include changed source files
**Files**: `zincSmithy/smithy/csp.smithy`, `zincCli/src/org/scala/abusers/zincCli/Server.scala`
**Description**: Add `changedFiles: ChangedFiles` (list of source path strings) to `CompileOutput` in Smithy. In zinc-cli Server, after `incrementalCompiler.compile()` returns, extract recompiled source file paths from the Zinc analysis and include in response.
**Purpose**: The indexing system needs to know which files changed so it can re-index only those files' TASTy. Without this, the entire project would need re-indexing after every save.
**Behavior**:

Protocol change in `csp.smithy`:
```smithy
structure CompileOutput {
  @required outputJar: String
  @required changedFiles: ChangedFiles
}
list ChangedFiles { member: String }
```

Zinc Server change: After `incrementalCompiler.compile(inputs, ZincCliLogger())` (line 139 of Server.scala):
1. If `compilationResult.hasModified`: cast `compilationResult.analysis()` to `sbt.internal.inc.Analysis`, get `analysis.relations.allSources` → convert via `.id` to absolute paths.
2. If `!hasModified`: return empty `changedFiles`.
3. **Simplest v1**: return all source `.id`s from current analysis (Zinc already tracks which were recompiled; this returns the full set which is fine for v1).

Current code (line 147): `result >> IO(csp.CompileOutput(outputClassJar.path.toString))` becomes: `result >> IO(csp.CompileOutput(outputClassJar.path.toString, changedFiles.toList))`
**Requirements**:
- Both client and server updated together (breaking protocol change)
- Zinc analysis accessible from `compilationResult.analysis()` (already called for `analysisStore.set`)
- Source paths must be absolute, convertible to URIs
- Empty list when `hasModified` is false
**Testing criteria**:
- Modify one file, recompile → `changedFiles` contains exactly that file
- No changes → `changedFiles` empty
- First compilation → all source files listed
- Paths are valid absolute paths

---

**Task**: Implement the index lifecycle manager
**File**: `sls/src/org/scala/abusers/sls/index/IndexManager.scala`
**Description**: Central coordinator wiring indexers, two-tier index, and server lifecycle. Handles: (1) initial dependency indexing during `initialized`, (2) initial project indexing from existing TASTy, (3) incremental project updates after compilation, (4) file deletion handling, (5) launching background source JAR resolution.
**Purpose**: Centralizes all index mutation triggers, ensuring correct ordering and resilience.
**Behavior**:
- `indexDependencies(targets: Set[ScalaBuildTargetInformation]): IO[Unit]` — collect unique classpath JARs across targets (excluding project classes dirs). For each JAR: if contains .tasty → `tastyIndexer.indexJar` → `dependencyIndex.addFromTasty`; else → `bytecodeIndexer.indexJar` → `dependencyIndex.addJar`. Parallel with `parEvalMapUnordered(maxConcurrent = 4)`. Launch background source resolution for ASM-indexed JARs.
- `indexExistingProjectArtifacts(targets: Set[ScalaBuildTargetInformation]): IO[Unit]` — for each target, check if `.sls/classes/{targetName}/` has .tasty files; if so, `tastyIndexer.indexDirectory` → `projectIndex.updateFiles`. Handles server restart with already-compiled project.
- `onCompilationComplete(target: ScalaBuildTargetInformation, compileOutput: CompileOutput): IO[Unit]` — extract `changedFiles` URIs, re-index .tasty in classes dir, `projectIndex.removeFiles(changedUris)` then `projectIndex.updateFiles(newResults)`.
- `onFilesDeleted(uris: Set[URI]): IO[Unit]` — `projectIndex.removeFiles(uris)`.
**Requirements**:
- Must not block server startup (dependency indexing as background fiber)
- Handle missing classpath entries gracefully
- Deduplicate classpath entries across targets
- Resilient: one JAR failure doesn't prevent others
- Correct source-to-TASTy mapping
**Testing criteria**:
- Classpath with Scala 3 + Scala 2 JARs → both indexed
- After indexing, `symbolIndex.searchSymbols` finds dep symbols
- After compilation with changed files → only those files updated
- File deletion → symbols removed immediately
- Failed JAR index → others still indexed
- Server restart → re-indexes from existing artifacts

---

**Task**: Wire IndexManager into server lifecycle and add new handler stubs
**Files**: `sls/src/org/scala/abusers/sls/SimpleLanguageServer.scala`, `sls/src/org/scala/abusers/sls/ServerImpl.scala`
**Description**: In `SimpleLanguageServer.server()`, create `TastyIndexer`, `BytecodeIndexer`, `ProjectIndex`, `DependencyIndex`, `SymbolIndex`, `IndexManager` and inject into `ServerImpl`. In `ServerImpl`: (1) trigger initial indexing in `initialized` after `bspStateManager.importBuild`, (2) pass `CompileOutput` to `IndexManager` in `handleDidSave`, (3) add stub handlers for new LSP operations.
**Purpose**: Integration glue connecting the index to existing server lifecycle without breaking existing functionality.
**Behavior**:

`SimpleLanguageServer.server()` additions (after existing component creation):
```scala
projectIndex    <- ProjectIndex.empty.toResource
dependencyIndex <- DependencyIndex.empty.toResource
symbolIndex     = SymbolIndex(projectIndex, dependencyIndex)
tastyIndexer    = TastyIndexer()
bytecodeIndexer = BytecodeIndexer()
indexManager    = IndexManager(symbolIndex, projectIndex, dependencyIndex, tastyIndexer, bytecodeIndexer, ...)
```
Pass `indexManager` and `symbolIndex` to `ServerImpl` constructor.

`ServerImpl.initialized` change: after `bspStateManager.importBuild`, fire `indexManager.indexDependencies(targets)` and `indexManager.indexExistingProjectArtifacts(targets)` as background fiber.

`ServerImpl.handleDidSave` change: after `bspStateManager.compileWithCSP(uri)` returns `compileOutput`, call `indexManager.onCompilationComplete(info, compileOutput)`.

New stub handlers: `textDocumentReferencesOp`, `workspaceSymbolOp`, `textDocumentRenameOp`, `textDocumentPrepareRenameOp`, `textDocumentImplementationOp`, `textDocumentPrepareTypeHierarchyOp`, `typeHierarchySupertypesOp`, `typeHierarchySubtypesOp`, `workspaceDidDeleteFiles`.
**Requirements**:
- Existing LSP functionality (completion, hover, definition, etc.) must not break
- Index creation must not slow server startup
- Background indexing must be cancellable on shutdown
- New handlers follow `computationQueue.synchronously` pattern
**Testing criteria**:
- Server starts and initializes without errors
- Existing completion/hover/definition still work
- After save, index update triggers
- Server shuts down cleanly during background indexing

---

## Phase 4 — Background: Source JAR Resolution

Can be developed in parallel with Phase 5.

---

**Task**: Implement lazy background source JAR resolution via Coursier
**File**: `sls/src/org/scala/abusers/sls/index/SourceJarResolver.scala`
**Description**: Resolves source JARs for dependency JARs indexed via ASM, extracts source positions for symbol navigation. Runs as background fiber, progressively updating `DependencyIndex` with resolved positions. Uses Coursier API (`io.get-coursier:interface:1.0.28`, already in deps).
**Purpose**: ASM indexing provides no source positions. Source JARs contain actual sources. Background resolution makes symbols navigable progressively without blocking startup.
**Behavior**:
- `resolveSourceJar(dependencyJarPath: String): IO[Option[os.Path]]` — infer Maven coords from Coursier cache layout path, download sources JAR with `Fetch.create().addDependencies(...).addClassifiers("sources").fetch()`. Cache in `Ref[IO, Map[String, Option[os.Path]]]`.
- `parseSourcePositions(jarPath: String, sourceJar: os.Path, symbols: List[IndexedSymbol]): IO[Map[SymbolId, Location]]` — open source JAR, for each .scala/.java file match against symbols by `SourceFile` attribute + name. Simple text scan for `class/trait/object/enum/def/val/var` + symbol name patterns.
- Background execution: `fs2.Stream.emits(asmJars).parEvalMapUnordered(4) { jar => resolve → parse → dependencyIndex.updateLocations }`.
**Requirements**:
- Must not block startup or any LSP request
- Handle missing source JARs (return None)
- Handle Coursier network failures (retry once, skip)
- Cache resolved paths
- Position parsing best-effort (unmatched → location stays None)
**Can be crude initially**: Position parsing as simple regex: `^\s*(class|trait|object|enum|def|val|var)\s+Name` → line number, column 0.
**Testing criteria**:
- Resolve source JAR for well-known library → valid path
- Source JAR contains expected files
- Position parsing finds `class` declarations
- Position parsing finds `def` declarations
- Non-existent JAR → None
- Network failure → no crash
- Results cached: second resolution instant
- Background fiber completes without blocking

---

## Phase 5 — LSP Features: Protocol Extensions & Handlers

Depends on Phases 1 and 3.

---

**Task**: Extend the LSP Smithy service with new operations
**File**: `slsSmithy/smithy/lsp.smithy`
**Description**: Add to `SlsLanguageServer` operations: `lsp#TextDocumentReferencesOp`, `lsp#WorkspaceSymbolOp`, `lsp#TextDocumentRenameOp`, `lsp#TextDocumentPrepareRenameOp`, `lsp#TextDocumentImplementationOp`, `lsp#TextDocumentPrepareTypeHierarchyOp`, `lsp#TypeHierarchySupertypesOp`, `lsp#TypeHierarchySubtypesOp`, `lsp#WorkspaceDidDeleteFiles`. Update `serverCapabilities` in `ServerImpl` to advertise: `referencesProvider`, `workspaceSymbolProvider`, `renameProvider`, `implementationProvider`, `typeHierarchyProvider`, workspace `fileOperations.didDelete`.
**Purpose**: Without protocol extensions, the client cannot invoke new features. Smithy code generator produces Scala traits and JSON-RPC bindings.
**Requirements**:
- Operation names use `lsp#` prefix to reference `lsp-smithy-definitions` types
- Smithy codegen produces matching Scala traits
- `ServerImpl` must implement all new abstract methods
- Server capabilities advertise new features
**Testing criteria**:
- Mill builds successfully with new Smithy ops
- Generated trait has all new methods
- `ServerImpl` compiles with implementations
- JSON-RPC method names match LSP spec

---

**Task**: Implement textDocument/references handler
**Location**: `ServerImpl.scala`
**Description**: Resolve symbol at cursor position, query `symbolIndex.getReferences(symbolId)`, optionally include declaration location if `context.includeDeclaration`. Convert to LSP Location objects.
**Purpose**: Find All References is one of the most-requested IDE features.
**Behavior**: Receive `ReferenceParams` → resolve symbol at (uri, position) → `symbolIndex.getReferences` → optionally add declaration → convert to LSP locations.
**Requirements**: Handle cursor not on symbol (empty), handle no references (empty/declaration only), include references from all project files, respect `includeDeclaration` flag.
**Can be crude initially**: Symbol resolution via `resolveSymbolAtPosition` with simple position containment. PC-based fallback added later.
**Testing criteria**: References for method used in 3 files → 3 locations. Unused symbol → empty. `includeDeclaration` flag respected. Cursor not on symbol → empty.

---

**Task**: Implement workspace/symbol handler
**Location**: `ServerImpl.scala`
**Description**: Search both index tiers for symbols matching query. Prefix matching for lowercase queries, CamelCase matching for queries with uppercase.
**Purpose**: Primary way users navigate types/definitions across entire project and deps.
**Behavior**: Receive `WorkspaceSymbolParams` → determine strategy (CamelCase vs prefix) → `symbolIndex.searchSymbols(query)` → convert to `SymbolInformation` → limit to 100 results.
**Requirements**: <100ms response. Prefix matching ("Li" → List). CamelCase ("ALT" → AbstractListType). Results from both tiers. Result limit enforced.
**Testing criteria**: Prefix query finds matches. CamelCase query finds matches. Empty query → limited/empty. Both tiers represented. Limit enforced.

---

**Task**: Implement implementation and type hierarchy handlers
**Location**: `ServerImpl.scala`
**Description**: Three related handlers: `textDocument/implementation` (find concrete implementations of abstract type/method), `typeHierarchy/supertypes` and `typeHierarchy/subtypes`.
**Purpose**: Type hierarchy navigation essential for understanding OOP structure.
**Behavior**: `implementation` → resolve symbol → `symbolIndex.getImplementations` (transitive concrete subtypes). `prepareTypeHierarchy` → resolve symbol → return `TypeHierarchyItem` with symbolId in `data`. `supertypes`/`subtypes` → extract symbolId from item → query index.
**Can be crude initially**: Transitive hierarchy one level deep initially.
**Testing criteria**: Trait implementation → all concrete classes. Supertypes → parent chain. Subtypes → children. Works across project/dep boundary.

---

**Task**: Implement rename and prepareRename handlers
**Location**: `ServerImpl.scala`
**Description**: `prepareRename` checks renameability (not dep, not synthetic). `rename` finds all references + declaration, computes `WorkspaceEdit` with text replacements.
**Purpose**: Project-wide rename refactoring.
**Behavior**: `prepareRename` → resolve → check project-only → return name range. `rename` → resolve → get references + declaration → group by file → `TextEdit` per reference → `WorkspaceEdit`.
**Can be crude initially**: Override chain and companion renaming deferred. V1 renames declaration + direct references only.
**Testing criteria**: Rename val → declaration + references updated. Rename class → declaration + type refs. Dep symbol → not renameable. WorkspaceEdit groups by file.

---

**Task**: Implement workspace/didDeleteFiles handler
**Location**: `ServerImpl.scala`
**Description**: On file deletion notification, immediately remove symbols/references from project index.
**Purpose**: Eager cleanup prevents stale results in find-references and workspace symbol.
**Behavior**: Receive `DeleteFilesParams` → filter to .scala → convert URIs → `indexManager.onFilesDeleted(uris)`.
**Requirements**: Handle bulk deletions, idempotent, fast.
**Testing criteria**: Delete file → symbols gone. Delete non-indexed file → no error. Bulk delete → all removed. Other files unaffected.

---

## Build System Change

**Task**: Add ASM dependency to sls module
**File**: `build.mill`
**Change**: Add `mvn"org.ow2.asm:asm:9.6"` to `sls.mvnDeps`. Note: `sls` has `moduleDeps = Seq(slsSmithy, profilingRuntime, zincCli)` so ASM is transitively available via zincCli, but explicit is cleaner. Also verify `scala3-tasty-inspector` is available at runtime from the Scala 3.7.4 distribution; if not, add `mvn"org.scala-lang::scala3-tasty-inspector:3.7.4"`.

---

## Dependency Graph

```
Phase 0 (no deps):
  0.1 IndexTypes
  0.2 PatriciaTrie
  0.3 BloomFilter
  0.4 CamelCaseUtils

Phase 1 (← Phase 0):
  1.1 ProjectIndex    ← 0.1, 0.2, 0.4
  1.2 DependencyIndex ← 0.1, 0.2, 0.3, 0.4
  1.3 SymbolIndex     ← 1.1, 1.2

Phase 2 (← Phase 0, parallel with Phase 1):
  2.1 TastyIndexer     ← 0.1
  2.2 BytecodeIndexer  ← 0.1

Phase 3 (← Phases 1, 2):
  3.1 CSP Extension    (independent protocol change)
  3.2 IndexManager     ← 1.1, 1.2, 1.3, 2.1, 2.2
  3.3 Server Wiring    ← 3.1, 3.2

Phase 4 (← Phase 1, parallel with Phase 5):
  4.1 SourceJarResolver ← 1.2

Phase 5 (← Phases 1, 3):
  5.1 Smithy Extensions (independent protocol change)
  5.2-5.6 LSP Handlers  ← 1.3, 5.1 (parallel after 5.1)
```

## Verification

- **Unit tests** (weaver-cats): Each data structure and indexer in isolation on sample TASTy/JAR/data
- **Incremental test**: Modify file, save, verify only that file's symbols re-indexed
- **Deletion test**: Delete file, verify didDeleteFiles removes symbols immediately
- **Integration test**: Compile testModule, index output, verify symbols found
- **Manual LSP test**: Open project in editor, trigger find references / workspace symbol / go-to-definition on dependency type
