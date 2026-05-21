# Indexing Scala 2 libraries from sources

## Status

Not implemented. Scala 2 deps currently go through `BytecodeIndexer` (see
`IndexManager.indexJarSafely` fall-through path): the Java-sources path returns
0 symbols (sources jar is `.scala`-only, `JavaIndexer.indexJarEntries` filters on
`.java`), that empty result is cached as a zero-byte file, and the bytecode path
then runs ASM over the `.class` files. Re-runs on every startup (bytecode path
is not cached). Locations are `None`, type signatures are JVM descriptors, names
are JVM-mangled.

## Why we'd want it

To improve fidelity on Scala 2 deps:

- Source locations → "go to definition" lands in `.scala` instead of nothing.
- Real Scala names/signatures instead of JVM-mangled (`$plus`, `MODULE$`, etc.).
- Picks up things bytecode can't (e.g. type aliases, given/implicit shape — but
  not their resolution, see fidelity caveats below).

## Options

### A. mtags (`org.scalameta:mtags-scala2`) — recommended first step

Syntactic indexer that emits semanticdb-flavored `SymbolInformation` from
`.scala` sources without running a compiler. This is what Metals uses to extract
symbols from jar sources. We already depend on `mtags-interfaces` for the
presentation compiler, so half the classloader-isolation scaffolding exists.

**Implementation shape (mirrors `JavaIndexer` 1:1):**

1. Add `mvn"org.scalameta::mtags-scala2:<ver>"` (cross-build for 2.13).
2. New `Scala2Indexer` next to `JavaIndexer`. Walks `.scala` entries in the
   `-sources.jar`, feeds each through mtags.
3. ClassLoader isolation: mtags-scala2 brings in scala-library_2.13, which
   conflicts with our Scala 3 stdlib on the same classloader. Use the same
   per-version isolated-classloader pattern as `PresentationCompilerProvider`.
4. Convert mtags' `SymbolInformation` → `IndexedSymbol`. Mostly mechanical
   (kind, visibility, owner, name — semanticdb already has all of these).
5. Wire into `IndexManager.indexJarSafely`: branch on the sources jar's content
   — call `Scala2Indexer` when entries end with `.scala`, `JavaIndexer` for
   `.java`. Both can write to `depIndexCache`.

**Fidelity:**

- ✅ Top-level classes/objects/traits, their members, package structure.
- ✅ Source locations (the main win over bytecode).
- ❌ Reference resolution — mtags doesn't type-check, so we can't index
  `SymbolReference`s the way `TastyIndexer` does. Hover would have names but
  not precise types.
- ❌ Macro-generated symbols. Whatever the macro produced at compile time isn't
  visible in the sources.

**Effort: ~2–4 days.**

### B. Embed scala-compiler_2.13

Same shape as `JavaFrontendDriver`/`JavaIndexer` but driving Scala 2's `Global`
through Parser + Typer, then harvesting the typed tree. Coursier (already wired
in `resolveHermeticDep`) supplies the per-jar typing classpath.

**Fidelity:** matches what `TastyIndexer` gives for Scala 3 — full typed
symbols, accurate signatures, resolvable references.

**Effort: ~2–3 weeks.** The cost is mostly:

- Linking the Scala 2 compiler (heavy dependency, ~25 MB) and managing its
  ClassLoader cleanly.
- Reconstructing the right Scala 2 stdlib version for each indexed jar (the
  jar's POM points at, e.g., `scala-library_2.12` vs `_2.13` — has to be
  routed correctly or the typer produces stubs).
- Symbol/type adapter from `scala.tools.nsc` types to our `IndexedSymbol`.
  More surface area than the dotc adapter because Scala 2's API is older and
  less uniform.

### C. Parse ScalaSignature from bytecode

Scala 2 stores its original signatures in a `ScalaSignature` attribute on
`.class` files. `scala-reflect` (or the compiler's `Pickler`) can decode it
back to typed symbols.

**Fidelity:** proper Scala types and names. **No source locations** (signatures
don't carry them).

**Effort: ~1–2 weeks.**

Probably not worth it given mtags gives source locations and bytecode already
gives us "something." Listed here for completeness.

## Recommendation

Do **A first**. It's the cheapest, slots cleanly into the existing
`indexJarSafely` branch, mirrors `JavaIndexer` so reviewing the diff is easy,
and Metals proves the approach scales to real-world Scala 2 jars. Source
locations alone are the largest user-visible win and `B`'s reference-resolution
fidelity is only worth pursuing once we have a concrete feature gap it would
close.

If we go further later, `B` is the next step — and at that point we'd likely
want it for project Scala 2 sources too (if we ever support them), not just
deps, so the investment compounds.
