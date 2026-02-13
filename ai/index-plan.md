# SLS Indexing System — Implementation Plan

## Context

SLS currently relies entirely on the Presentation Compiler (PC) for IDE features. The PC works per-file with a classpath, providing completion, hover, definition, signature help, inlay hints, and diagnostics. However, cross-module features like **find references**, **workspace symbol search**, **rename**, **call hierarchy**, **type hierarchy**, and **implementations** require a global index of all symbols and their relationships across the entire project and its dependencies.

The indexing system will:
- Read TASTy / best-effort TASTy files produced by zinc-cli for Scala 3 project sources
- Read .class files from dependency JARs for Scala 2 and Java libraries (via ASM)
- Resolve source JARs for dependencies via Coursier
- Incrementally update at **file-level granularity** using changed-file lists from zinc-cli's `CompileOutput`
- React to `workspace/didDeleteFiles` for eager index cleanup
- Store everything in-memory with a **two-tier architecture**: immutable dependency index + mutable project index

---

## Architecture: Two-Tier Index

### Tier 1 — `DependencyIndex` (immutable per session)
- Populated once during `initialized` from classpath JARs
- Contains Scala 2 and Java symbols read via ASM bytecode analysis
- Never invalidated during normal operation (only on build re-import)
- No references tracked (dependencies are declaration-only)
- Source locations resolved lazily via Coursier on first navigation

### Tier 2 — `ProjectIndex` (mutable, file-level granularity)
- Populated from TASTy / best-effort TASTy produced by zinc-cli
- Tracks both **definitions** and **references** for all project source files
- Invalidated at **file level** using `changedFiles` returned by zinc-cli in `CompileOutput`
- Also reacts to `workspace/didDeleteFiles` for eager removal of deleted file symbols
- Each symbol and reference is tagged with its source file URI for precise invalidation

### Combined queries span both tiers transparently via `SymbolIndex` facade.

---

## Search Data Structures & Performance

### Primary: Patricia Trie for name-based search

Both tiers use a **Patricia Trie (compressed radix tree)** for symbol name lookups. This is the standard data structure for IDE autocompletion and workspace symbol search.

**Why Patricia Trie:**
- O(k) prefix search where k = query length (independent of index size)
- Natural prefix enumeration — iterate all symbols starting with "Li" without scanning the full index
- Incremental updates — insert/remove individual entries without rebuilding
- Memory-efficient when names share prefixes (e.g. all `scala.collection.*` symbols)

**Implementation — `PatriciaTrie.scala`:**
```scala
class PatriciaTrie[V]:
  def insert(key: String, value: V): PatriciaTrie[V]
  def remove(key: String): PatriciaTrie[V]
  def prefixSearch(prefix: String): List[V]
  def exactLookup(key: String): Option[V]
```

Each index tier maintains a trie keyed on **lowercased simple names** → `Set[SymbolId]`. On insertion of `IndexedSymbol`, the simple name is lowercased and added to the trie.

### Secondary: CamelCase index

A second Patricia Trie indexed on **extracted CamelCase characters** for CamelCase matching:
- `AbstractListType` → key `"alt"` (lowercase of `"ALT"`)
- `ListBuffer` → key `"lb"`
- Query `"ALT"` → trie lookup on `"alt"` → finds `AbstractListType`

Built as a companion to the primary trie — both updated together on insert/remove.

### Bloom filter per dependency JAR

For workspace symbol queries that need to scan across many dependency JARs, a **Bloom filter per JAR** provides fast "definitely not in this JAR" pre-filtering:
- Before scanning a JAR's symbols in the trie, check the Bloom filter
- ~1KB per JAR, false positive rate <1% with 10 hash functions
- Avoids iterating JARs that can't possibly match

### Memory optimizations

- **String interning** for repeated package names and common type names (`toString`, `hashCode`, `apply`, `scala.`, `java.lang.`)
- **Array-backed reference storage** — `Array[SymbolReference]` instead of `List` for cache locality and lower GC pressure
- **URI table** — many symbols share the same source file URI; store URIs in a `Vector[URI]` and reference by index to avoid duplication
- **Lazy signatures** — `signature: String` can be computed on-demand from TASTy/ASM data rather than stored eagerly for all symbols

### Query performance targets

| Query | Expected latency | Data structure used |
|-------|-----------------|---------------------|
| Prefix name search (`"Li"` → `List`, `ListBuffer`) | <2ms | Primary Patricia Trie |
| CamelCase search (`"ALT"` → `AbstractListType`) | <2ms | CamelCase Patricia Trie |
| Exact name lookup (`"List"`) | <0.1ms | Primary Patricia Trie |
| Find references by SymbolId | <1ms | `Map[SymbolId, Array[SymbolReference]]` direct lookup |
| Subtypes/supertypes | <1ms | `Map[SymbolId, Set[SymbolId]]` direct lookup |
| File-level invalidation | <5ms | `Map[URI, Set[SymbolId]]` + trie remove |

---

## Phase 1: Index Data Model

### New package: `org.scala.abusers.sls.index`

### 1.1 Core types — `IndexTypes.scala`

```scala
package org.scala.abusers.sls.index

import java.net.URI

/** Fully qualified symbol identifier, e.g. "scala.collection.immutable.List#map" */
opaque type SymbolId = String
object SymbolId:
  def apply(s: String): SymbolId = s
  extension (s: SymbolId) def value: String = s

enum SymbolKind:
  case Class, Trait, Object, Enum, Method, Val, Var,
       TypeAlias, TypeParam, Package, Constructor, Field, EnumCase

enum Visibility:
  case Public, Protected, Private, PackagePrivate

case class Location(
  uri: URI,
  startLine: Int, startCol: Int,
  endLine: Int, endCol: Int,
)

case class IndexedSymbol(
  id: SymbolId,
  name: String,                // simple name (e.g. "map")
  kind: SymbolKind,
  location: Option[Location],  // None for deps without source JARs
  sourceFile: Option[URI],     // source file this came from (for file-level invalidation)
  owner: Option[SymbolId],     // containing class/object/package
  visibility: Visibility,
  signature: String,           // human-readable type signature
  parents: List[SymbolId],     // superclasses + implemented traits
  overrides: List[SymbolId],   // symbols this overrides
  typeParams: List[String],
  origin: SymbolOrigin,
)

enum SymbolOrigin:
  case ProjectTasty(buildTarget: String, sourceFile: URI)
  case DependencyClassfile(jarPath: String)

case class SymbolReference(
  symbol: SymbolId,
  location: Location,
  referenceKind: ReferenceKind,
)

enum ReferenceKind:
  case Call, TypeRef, Import, Override, Extends, Annotation
```

### 1.2 Project index state — `ProjectIndex.scala`

Mutable index for project sources, supports file-level invalidation:

```scala
class ProjectIndex private (state: Ref[IO, ProjectIndexState]):
  // Queries
  def getSymbol(id: SymbolId): IO[Option[IndexedSymbol]]
  def getSymbolsByName(name: String): IO[List[IndexedSymbol]]
  def searchSymbols(query: String): IO[List[IndexedSymbol]]        // prefix/fuzzy
  def getSymbolsInFile(uri: URI): IO[List[IndexedSymbol]]
  def getReferences(id: SymbolId): IO[List[SymbolReference]]
  def getReferencesInFile(uri: URI): IO[List[SymbolReference]]
  def getSubtypes(id: SymbolId): IO[Set[SymbolId]]
  def getSupertypes(id: SymbolId): IO[List[SymbolId]]

  // File-level mutations
  def updateFiles(files: Map[URI, (List[IndexedSymbol], List[SymbolReference])]): IO[Unit]
  def removeFiles(uris: Set[URI]): IO[Unit]

case class ProjectIndexState(
  symbols: Map[SymbolId, IndexedSymbol],
  nameTrie: PatriciaTrie[Set[SymbolId]],         // lowercased name → symbols (prefix search)
  camelCaseTrie: PatriciaTrie[Set[SymbolId]],    // CamelCase chars → symbols
  byFile: Map[URI, Set[SymbolId]],              // for file-level invalidation
  references: Map[SymbolId, Array[SymbolReference]], // array for cache locality
  refsByFile: Map[URI, Array[SymbolReference]],   // for file-level invalidation
  subtypes: Map[SymbolId, Set[SymbolId]],        // inverted parent→children
)
```

### 1.3 Dependency index state — `DependencyIndex.scala`

Immutable index for external JARs:

```scala
class DependencyIndex private (state: Ref[IO, DependencyIndexState]):
  // Queries only — no mutation after initial population
  def getSymbol(id: SymbolId): IO[Option[IndexedSymbol]]
  def getSymbolsByName(name: String): IO[List[IndexedSymbol]]
  def searchSymbols(query: String): IO[List[IndexedSymbol]]
  def getSubtypes(id: SymbolId): IO[Set[SymbolId]]
  def getSupertypes(id: SymbolId): IO[List[SymbolId]]

  // Population (called during initialization)
  def addJar(jarPath: String, symbols: List[IndexedSymbol]): IO[Unit]
  def addFromTasty(jarPath: String, results: Map[URI, (List[IndexedSymbol], List[SymbolReference])]): IO[Unit]

  // Background source resolution updates
  def updateLocations(jarPath: String, positions: Map[SymbolId, Location]): IO[Unit]

case class DependencyIndexState(
  symbols: Map[SymbolId, IndexedSymbol],
  nameTrie: PatriciaTrie[Set[SymbolId]],         // lowercased name → symbols
  camelCaseTrie: PatriciaTrie[Set[SymbolId]],    // CamelCase chars → symbols
  bloomFilters: Map[String, BloomFilter],        // jarPath → bloom filter for quick exclusion
  byJar: Map[String, Set[SymbolId]],
  subtypes: Map[SymbolId, Set[SymbolId]],
)
```

### 1.4 Combined facade — `SymbolIndex.scala`

```scala
class SymbolIndex(project: ProjectIndex, dependency: DependencyIndex):
  /** Queries merge results from both tiers */
  def getSymbol(id: SymbolId): IO[Option[IndexedSymbol]]
  def getSymbolsByName(name: String): IO[List[IndexedSymbol]]
  def searchSymbols(query: String): IO[List[IndexedSymbol]]
  def getReferences(id: SymbolId): IO[List[SymbolReference]]
  def getSubtypes(id: SymbolId): IO[Set[SymbolId]]
  def getSupertypes(id: SymbolId): IO[List[SymbolId]]
  def getImplementations(id: SymbolId): IO[List[IndexedSymbol]]  // transitive subtypes
```

**Files to create:**
- `sls/src/org/scala/abusers/sls/index/IndexTypes.scala`
- `sls/src/org/scala/abusers/sls/index/PatriciaTrie.scala`
- `sls/src/org/scala/abusers/sls/index/BloomFilter.scala`
- `sls/src/org/scala/abusers/sls/index/ProjectIndex.scala`
- `sls/src/org/scala/abusers/sls/index/DependencyIndex.scala`
- `sls/src/org/scala/abusers/sls/index/SymbolIndex.scala`

---

## Phase 2: TASTy Inspector Integration (Scala 3 Project Sources)

### 2.1 TASTy indexer — `TastyIndexer.scala`

Uses `scala.tasty.inspector.TastyInspector` to walk TASTy trees and extract symbols + references.

```scala
class TastyIndexer:
  /** Index specific .tasty files (for incremental: only the changed ones) */
  def indexFiles(tastyFiles: List[Path], classpath: List[Path], buildTarget: String):
    IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]]

  /** Index all .tasty files in a directory (for initial population) */
  def indexDirectory(classesDir: Path, classpath: List[Path], buildTarget: String):
    IO[Map[URI, (List[IndexedSymbol], List[SymbolReference])]]
```

**Note:** Results are keyed by source file URI so they can be fed directly into `ProjectIndex.updateFiles()`.

**TASTy tree walking strategy:**
- Implement `Inspector` trait from `scala.tasty.inspector`
- In `inspect(using Quotes)`, walk recursively:
  - `ClassDef` → class/trait/object/enum, record parent types
  - `DefDef` → methods, record override relationships
  - `ValDef` → val/var
  - `TypeDef` → type aliases
  - `Term.Ident` / `Term.Select` → references to other symbols
  - `TypeTree` → type references
  - `Import` → import references
- Use `Symbol.fullName` for `SymbolId` generation
- Use `Position` for source locations and source file attribution
- Use `Symbol.flags` for visibility
- Use `Symbol.typeRef.show` for human-readable signatures
- Handle best-effort TASTy gracefully: wrap individual symbol extraction in try/catch, skip symbols with missing types rather than failing the whole file

**Classpath requirement:** TASTy Inspector needs the compilation classpath. Get from `ScalaBuildTargetInformation.classpath`.

**Files to create:**
- `sls/src/org/scala/abusers/sls/index/TastyIndexer.scala`
- `build.mill` — may need `scala3-tasty-inspector` dep explicitly

---

## Phase 3: Dependency Indexing (Scala 3 TASTy + Scala 2/Java ASM)

### 3.1 Dependency classification

Dependency JARs are classified and indexed differently:

| Dependency type | Detection | Indexer | Position quality |
|----------------|-----------|---------|------------------|
| **Scala 3** | JAR contains `.tasty` files | TASTy Inspector | Full positions immediately |
| **Scala 2** | JAR has `.class` only, Scala signature annotations | ASM | No positions → background source JAR resolution |
| **Java** | JAR has `.class` only, no Scala markers | ASM | No positions → background source JAR resolution |

### 3.2 Scala 3 dependency indexing

Reuses `TastyIndexer` from Phase 2. Same code path as project sources but results go into `DependencyIndex` instead of `ProjectIndex`.

```scala
// In IndexManager, during dependency indexing:
if jarContainsTasty(jarPath) then
  tastyIndexer.indexJar(jarPath, classpath, buildTarget)
    .flatMap(results => dependencyIndex.addFromTasty(jarPath, results))
else
  bytecodeIndexer.indexJar(jarPath)
    .flatMap(symbols => dependencyIndex.addJar(jarPath, symbols))
```

### 3.3 ASM bytecode indexer — `BytecodeIndexer.scala`

Used only for Scala 2 and Java dependency JARs.

```scala
class BytecodeIndexer:
  def indexJar(jarPath: Path): IO[List[IndexedSymbol]]
```

**What to extract:**
- `ClassVisitor.visit()` → class name, superclass, interfaces → `IndexedSymbol` + parents
- `visitMethod()` → method name, descriptor, generic signature → `IndexedSymbol`
- `visitField()` → field name, descriptor → `IndexedSymbol`
- `SourceFile` attribute → original filename (for later source JAR matching)
- Parse generic signatures with `SignatureReader` for type parameter info

**No references tracked** — ASM only gives declarations.

**Locations initially `None`** — filled in asynchronously by background source resolution (Phase 5).

**Future improvement:** Consider using [Google Turbine](https://github.com/google/turbine) for Java source parsing. Turbine is a fast Java header compiler that can parse Java source files and extract declarations with exact positions. Useful for: (1) indexing Java project sources without full compilation, (2) parsing Java source JARs for precise positions instead of regex heuristics. Not needed initially — evaluate when implementing Java-specific indexing.

### 3.4 Background source location resolution

After initial ASM indexing, a **background fiber** progressively resolves source JARs and updates symbol locations:

```scala
// Launched as a background fiber during initialization:
def resolveSourceLocationsInBackground(
  asmIndexedJars: List[String]
): IO[Unit] =
  fs2.Stream.emits(asmIndexedJars)
    .parEvalMapUnordered(maxConcurrent = 4) { jarPath =>
      sourceJarResolver.resolveSourceJar(jarPath).flatMap {
        case Some(sourceJar) =>
          sourceJarResolver.parseSourcePositions(jarPath, sourceJar)
            .flatMap(positions => dependencyIndex.updateLocations(jarPath, positions))
        case None => IO.unit  // no source JAR available
      }
    }
    .compile.drain
```

This runs without blocking server startup. Symbols become navigable progressively as source JARs are resolved.

**Files to create/modify:**
- `sls/src/org/scala/abusers/sls/index/BytecodeIndexer.scala` — new
- `build.mill` — add `mvn"org.ow2.asm:asm:9.6"` to `sls` module deps

---

## Phase 4: CSP Protocol Extension + Incremental Updates

### 4.1 Extend `CompileOutput` in CSP protocol

**File:** `zincSmithy/smithy/csp.smithy`

```smithy
structure CompileOutput {
  @required
  outputJar: String

  @required
  changedFiles: ChangedFiles    // NEW: files that were recompiled
}

list ChangedFiles {
  member: String  // source file paths that changed
}
```

**File:** `zincCli/src/org/scala/abusers/zincCli/Server.scala`

After `incrementalCompiler.compile(inputs, ...)`, extract changed source files from `compilationResult.analysis()` and include them in the `CompileOutput`. Zinc's `Analysis` API provides `relations.allSources` and we can diff against previous analysis to find changed files, or use `compilationResult.hasModified` + stamp changes.

### 4.2 IndexManager — `IndexManager.scala`

```scala
class IndexManager(
  index: SymbolIndex,
  projectIndex: ProjectIndex,
  dependencyIndex: DependencyIndex,
  tastyIndexer: TastyIndexer,
  bytecodeIndexer: BytecodeIndexer,
  sourceJarResolver: SourceJarResolver,
):
  /** Called during `initialized` — index all dependency JARs */
  def indexDependencies(targets: Set[ScalaBuildTargetInformation]): IO[Unit]

  /** Called during `initialized` — index existing TASTy from previous sessions */
  def indexExistingProjectArtifacts(targets: Set[ScalaBuildTargetInformation]): IO[Unit]

  /** Called after zinc-cli compile — file-level incremental update */
  def onCompilationComplete(
    target: ScalaBuildTargetInformation,
    compileOutput: CompileOutput,  // contains outputJar + changedFiles
  ): IO[Unit]

  /** Called on workspace/didDeleteFiles — eager removal */
  def onFilesDeleted(uris: Set[URI]): IO[Unit]
```

### 4.3 Incremental update flow (file-level)

When zinc-cli returns `CompileOutput` with `changedFiles`:

1. Convert `changedFiles` paths to source file URIs
2. Remove old symbols/references for those files: `projectIndex.removeFiles(changedUris)`
3. Find corresponding `.tasty` files for the changed sources in the unzipped classes dir
4. Re-index only those TASTy files: `tastyIndexer.indexFiles(changedTastyPaths, ...)`
5. Insert new symbols + references: `projectIndex.updateFiles(results)`

When `workspace/didDeleteFiles` fires:

1. Extract deleted file URIs
2. `projectIndex.removeFiles(deletedUris)` — immediate cleanup

### 4.4 Mapping source files to TASTy files

The TASTy file for `src/main/scala/com/example/Foo.scala` (containing `class Foo`) will be at `classes/com/example/Foo.tasty`. We can:
- Walk TASTy files and check their `Position.sourceFile` to match
- Or maintain a `sourceFile → tastyFile` mapping built during indexing

### 4.5 Integration with `ServerImpl.scala`

**Modified `handleDidSave`** — pass compile output to index manager:
```scala
// Current flow + index update:
bspStateManager.compileWithCSP(uri).flatMap { compileOutput =>
  updateOutputClasspath(info, os.Path(compileOutput.outputJar)) *>
  indexManager.onCompilationComplete(info, compileOutput)
}
```

**Modified `initialized`** — trigger initial indexing after build import:
```scala
bspStateManager.importBuild *>
indexManager.indexDependencies(targets) *>
indexManager.indexExistingProjectArtifacts(targets)
```

**New handler** — `workspace/didDeleteFiles`:
```scala
def handleDidDeleteFiles(params: DeleteFilesParams): IO[Unit] =
  indexManager.onFilesDeleted(params.files.map(f => URI(f.uri)).toSet)
```

**New LSP handlers** querying the index:
- `textDocument/references` → `index.getReferences(symbolId)`
- `workspace/symbol` → `index.searchSymbols(query)`
- `textDocument/rename` → `index.getReferences(symbolId)` + apply edits
- `textDocument/implementation` → `index.getImplementations(symbolId)`
- `textDocument/typeHierarchy/supertypes` → `index.getSupertypes(symbolId)`
- `textDocument/typeHierarchy/subtypes` → `index.getSubtypes(symbolId)`

**Symbol ID resolution at cursor:** When user triggers "find references", resolve `SymbolId` by:
1. Get document position from LSP params
2. Look up symbols in `projectIndex.getSymbolsInFile(uri)` matching the position
3. Or use PC's `definition()` to get the target symbol's location, then find it in the index

**Files to create/modify:**
- `zincSmithy/smithy/csp.smithy` — extend `CompileOutput`
- `zincCli/src/org/scala/abusers/zincCli/Server.scala` — populate `changedFiles`
- `sls/src/org/scala/abusers/sls/index/IndexManager.scala` — new
- `sls/src/org/scala/abusers/sls/ServerImpl.scala` — wire index into handlers
- `sls/src/org/scala/abusers/sls/SimpleLanguageServer.scala` — create IndexManager

---

## Phase 5: Source JAR Resolution (Background)

### 5.1 SourceJarResolver — `SourceJarResolver.scala`

```scala
class SourceJarResolver(cache: Ref[IO, Map[String, Option[Path]]]):
  /** Resolve source JAR for a dependency JAR via Coursier */
  def resolveSourceJar(dependencyJarPath: String): IO[Option[Path]]

  /** Parse a source JAR and extract positions for all declarations.
    * Matches ASM-discovered symbols (by SourceFile attribute + class/method name)
    * to their exact source positions. */
  def parseSourcePositions(
    dependencyJarPath: String,
    sourceJarPath: Path,
  ): IO[Map[SymbolId, Location]]
```

**Source JAR resolution strategy:**
- Uses Coursier API (`io.get-coursier:interface:1.0.28`, already in deps)
- Infer Maven coordinates from JAR path (Coursier cache layout: `~/.cache/coursier/v1/.../group/artifact/version/artifact-version.jar`)
- Download `artifact-version-sources.jar` with `Fetch.create().addClassifiers("sources")`
- Cache resolved paths in `Ref`

**Source position parsing strategy:**
- Open source JAR, iterate source files
- For `.java` files: regex/heuristic scan for `class Foo`, `interface Bar`, method signatures
- For `.scala` files: regex/heuristic scan for `class`, `trait`, `object`, `def`, `val` declarations
- Match against the ASM-extracted symbol names + `SourceFile` attribute
- Produce `Map[SymbolId, Location]` with exact line/column positions

**Execution model:** Runs as a background fiber (see Phase 3.4). Symbols become navigable progressively. The `DependencyIndex.updateLocations` method atomically updates symbol locations as they are resolved.

**File to create:**
- `sls/src/org/scala/abusers/sls/index/SourceJarResolver.scala`

---

## Phase 6: LSP Protocol Extensions

### 6.1 New Smithy operations in `lsp.smithy`

Add to `SlsLanguageServer`:
- `TextDocumentReferencesOp`
- `WorkspaceSymbolOp`
- `TextDocumentRenameOp` + `TextDocumentPrepareRenameOp`
- `TextDocumentImplementationOp`
- `TextDocumentTypeHierarchyPrepareOp` + supertypes/subtypes
- `WorkspaceDidDeleteFilesOp` (for eager index cleanup)

### 6.2 Updated `serverCapabilities`

```scala
referencesProvider = true
workspaceSymbolProvider = true
renameProvider = true
implementationProvider = true
typeHierarchyProvider = true
```

**File to modify:**
- `slsSmithy/smithy/lsp.smithy`

---

## File Summary

| File | Action | Description |
|------|--------|-------------|
| `sls/src/.../index/IndexTypes.scala` | Create | Core types: SymbolId, IndexedSymbol, SymbolReference, enums |
| `sls/src/.../index/PatriciaTrie.scala` | Create | Compressed radix tree for O(k) prefix search on symbol names |
| `sls/src/.../index/BloomFilter.scala` | Create | Per-JAR bloom filter for fast search pre-filtering |
| `sls/src/.../index/ProjectIndex.scala` | Create | Mutable project index with file-level invalidation, dual tries |
| `sls/src/.../index/DependencyIndex.scala` | Create | Immutable dependency index with bloom filters per JAR |
| `sls/src/.../index/SymbolIndex.scala` | Create | Facade merging both tiers for queries |
| `sls/src/.../index/TastyIndexer.scala` | Create | TASTy Inspector walker, returns results keyed by source file |
| `sls/src/.../index/BytecodeIndexer.scala` | Create | ASM reader for Scala 2/Java JARs |
| `sls/src/.../index/IndexManager.scala` | Create | Lifecycle coordinator, incremental updates, deletion handling |
| `sls/src/.../index/SourceJarResolver.scala` | Create | Lazy Coursier-based source JAR resolution |
| `zincSmithy/smithy/csp.smithy` | Modify | Add `changedFiles` to `CompileOutput` |
| `zincCli/src/.../Server.scala` | Modify | Populate `changedFiles` from Zinc analysis |
| `sls/src/.../ServerImpl.scala` | Modify | Wire index into didSave, initialized, new LSP handlers, didDelete |
| `sls/src/.../SimpleLanguageServer.scala` | Modify | Create IndexManager, wire into server |
| `slsSmithy/smithy/lsp.smithy` | Modify | Add references, workspace/symbol, rename, implementation, type hierarchy, didDelete ops |
| `build.mill` | Modify | Add ASM to `sls` deps, ensure tasty-inspector available |

---

## Implementation Order

1. **Phase 1** — Data model (`IndexTypes`, `ProjectIndex`, `DependencyIndex`, `SymbolIndex`)
2. **Phase 2** — TASTy indexer — testable standalone on sample TASTy
3. **Phase 3** — ASM indexer — testable standalone on sample JARs
4. **Phase 4** — CSP extension + IndexManager + ServerImpl wiring (the glue)
5. **Phase 5** — Source JAR resolution
6. **Phase 6** — LSP Smithy protocol extensions + handler implementations

Phases 2 and 3 can be developed in parallel.

---

## Verification

- **Unit tests**: Index each indexer in isolation on sample TASTy/JAR files (weaver-cats)
- **Incremental test**: Modify a file, save, verify only that file's symbols are re-indexed (check `changedFiles` in CompileOutput)
- **Deletion test**: Delete a file, verify `didDeleteFiles` removes its symbols immediately
- **Integration test**: Compile exampleZincCliClient, index output, verify symbols found
- **Manual LSP test**: Open project in editor, trigger find references / workspace symbol / go-to-definition on a dependency type
