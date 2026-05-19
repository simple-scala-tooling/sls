# weaver-core: shaded TASTy doesn't match shaded bytecode

## Symptom

Indexing `weaver-core_3-0.12.0.jar` via the TASTy inspector crashes with:

```
assertion failed: class MyersDiff has non-class parent:
  AppliedType(TypeRef(TermRef(TermRef(...,object munit),object diff),DiffAlgorithm), …)
```

Dotc's TASTy unpickler can't resolve `munit.diff.DiffAlgorithm` on the classpath,
falls back to a stub denotation whose prefix is a `TermRef` to an "object" instead
of a `PackageRef`, and erasure later trips the assertion when computing the
signature.

## Cause

The jar contains the munit-diff classes at a **shaded** location:

```
weaver/internal/shaded/munit/diff/MyersDiff.class
weaver/internal/shaded/munit/diff/MyersDiff.tasty
weaver/internal/shaded/munit/diff/DiffAlgorithm.class
…
```

The `.class` bytecode was rewritten to the new package by the shading tool, but
the **`.tasty` files were not** — they still contain references to the original
`munit.diff.*` package. So dotc reads
`weaver/internal/shaded/munit/diff/MyersDiff.tasty`, sees
`extends munit.diff.DiffAlgorithm`, and looks for that on the classpath — but
`munit.diff.DiffAlgorithm` doesn't exist anywhere in the jar, and weaver's POM
comments out the munit-diff dependency declaration:

```xml
<!-- shaded dependency org.scalameta:munit-diff_3
  <dependency>
    <groupId>org.scalameta</groupId>
    <artifactId>munit-diff_3</artifactId>
    <version>1.2.4</version>
  </dependency>
-->
```

so coursier won't fetch it either. The reference is unresolvable for any
classpath we can plausibly construct.

This is a general pitfall of byte-level shading (sbt-assembly,
maven-shade-plugin): both predate TASTy and only rewrite `.class` content. The
TASTy attribute embedded in classfiles is opaque to them.

## What sls does

The TASTy inspector path in `IndexManager.indexJarSafely` catches the failure
and routes to `bytecodeIndexer` instead. The bytecode *was* rewritten correctly
during shading, so the bytecode indexer produces valid symbols for the shaded
classes. The user sees one `WARN  TASTy indexing failed for …weaver-core…`
per server start; the index is otherwise complete.

## What weaver could do upstream (none required of sls)

- **Strip TASTy from shaded classes.** Drop the TASTy attribute / `.tasty`
  companion file for any class moved by the shader. Dotc reads bytecode
  signatures instead, which work. Lose inline/macro support for the shaded
  code only — fine for utility code like munit-diff. Lowest-effort fix.
- **Stop shading.** Depend on `munit-diff_3` normally. munit-diff is small
  and stable; the classpath-pollution cost is usually less than the
  shading complexity.
- **TASTy-aware shading.** Rewrite both bytecode *and* TASTy. No standard
  tool exists; would have to roll one (TASTy is documented, names live in a
  string table).
- **Vendor sources.** Copy munit-diff sources into the shaded package and
  recompile; the compiler then emits TASTy with the correct package.
  Highest maintenance cost, most correct semantically.

Filing a bug against weaver pointing at option 1 is the realistic path.
