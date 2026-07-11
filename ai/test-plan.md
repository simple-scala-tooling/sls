# SLS test plan: scenarios, invariants, and harness work

Successor to the phasing section of `test-infrastructure.md` (whose Proposals 2–4 are implemented;
see "Harness inventory" below). This document is the work queue: each item is self-contained so an
implementing agent can pick it up without re-deriving context. Work items are keyed (`H*`, `Z*`,
`3.*`, `P*`, `I*`) and list their prerequisites explicitly.

## Why these tests

SLS must not inherit the failure modes that define Metals' and Bloop's reputations. The taxonomy:

- **A. Stale state that never converges** — "restart Metals to fix it". Fixed code still shows
  errors, indexes reflect old code, nothing reconciles.
- **B. Event-ordering races** — responses computed against stale document versions, cancellation
  not honored, lost updates under rapid saves.
- **C. External-change blindness** — `git checkout`, terminal compiles, codegen desync the server.
- **D. Degradation instead of resilience** — one bad file kills features workspace-wide; the
  compile server dies and nothing recovers. Bloop's version: a poisoned zinc analysis store,
  fixed only by "delete `.bloop` and reimport".
- **E. Cold-start wall** — out of scope for now (product decision pending on partial service
  during indexing).

SLS owns its compile server (zincCli over CSP; Bloop is not in the picture — BSP structure comes
from Mill directly). So Bloop's failure modes are not "react gracefully" scenarios — they are
must-not-happen properties of our own component, tested directly.

## The two spine invariants

Almost every test below is an instance of one of these. When in doubt about what to assert,
assert convergence against the oracle.

**LSP convergence (Tier 3):** after *any* sequence of editor events, server-observable state
(symbol index, published diagnostics) is identical to a freshly started server on the final disk
content. If this holds, "restart the server" is provably a no-op and staleness bugs cannot persist.

**Compile convergence (Tier Z):** after *any* sequence of incremental compiles, the output
(jar entries + contents, diagnostics, `changedFiles` closure) is identical to a clean compile of
the final sources. If this holds, "delete the analysis store and rebuild" is provably a no-op.

Both use the same oracle pattern: rebuild from scratch, compare. Both generalize later into
property tests (random edit sequences vs. the oracle).

## Ground rules for every test

- Weaver `SimpleIOSuite`, `cats.effect.IO`, braces (no significant indentation), `maxColumn = 120`.
- **No `IO.sleep`-based assertions.** Await observable state via `TestClient.awaitUntil`
  (`sls/test/src/org/scala/abusers/sls/util/TestClient.scala`) or assert on version numbers /
  hooked stubs. A test that needs a sleep to pass is a test we don't merge.
- ASCII-only test names (mill test-runner quirk).
- All temp state under `os.temp.dir(prefix = "sls-test-...")`, released via `Resource`.
- Verification loop: `./mill __.compile`, then `./mill sls.test` (or the new module's `.test`).
  Known cosmetic noise: `NoClassDefFoundError: ...UnsafeNonFatal` during teardown is mill#7066 —
  ignore it. `--only` may under-report; trust the full module run.
- Stubs must **fail loudly** on unsupported paths (see `BspStubs.nope`), never hang.
- Every test's failure message should say what invariant broke, not just `expected true`.

## Harness inventory (already implemented)

| Piece | Path | Role |
|---|---|---|
| `code"""..."""` marker DSL | `sls/test/src/org/scala/abusers/sls/index/util/Code.scala` | positions without hardcoding |
| `TestWorkspace` | `sls/test/src/org/scala/abusers/sls/index/util/TestWorkspace.scala` | in-process dotc → temp classes dir, live index |
| `TestClient` | `sls/test/src/org/scala/abusers/sls/util/TestClient.scala` | `SlsLanguageClient[IO]` double; editor-grammar verbs; captures diagnostics |
| `TestServer` | `sls/test/src/org/scala/abusers/sls/util/TestServer.scala` | wires real `ServerImpl` with BSP/CSP/PC/queue stubs; already takes `cspServer: Option[CspServer[IO]]` override |
| `BspStubs` / `FakeCspServer` | `sls/test/src/org/scala/abusers/sls/util/BspCspStubs.scala` | fail-loud BSP stub; dotc-backed CSP double (full compile, `changedFiles = Map.empty`) |
| `TestComputationQueue` | `sls/test/src/org/scala/abusers/sls/util/TestComputationQueue.scala` | passthrough queue; drops `pushSync` (see H1) |

Existing full-stack tests: `TestClientSpec` (open/edit/save→index, determinism, buffer-vs-disk).
Keep the frozen fixture JARs used by index specs — they are the TASTy-version-drift suite;
`TestWorkspace` only ever compiles with the classpath compiler (see I2).

## Known production facts agents need

- **The deferred classpath swap is CWD-relative.** `ScalaBuildTargetInformation.classesDir` /
  `classJarPath` hardcode `./.sls/classes/<displayName>` resolved against process CWD
  (`sls/src/org/scala/abusers/sls/BspStateManager.scala:26-38`). `ServerImpl.handleDidSave`
  defers `deleteRecursively + unzip + invalidateCompilers` via `computationQueue.pushSync`
  (`sls/src/org/scala/abusers/sls/ServerImpl.scala:274-293`); `ComputationQueueImpl.synchronously`
  drains pending syncs before the next computation (`sls/src/org/scala/abusers/sls/ComputationQueue.scala:17-22`).
  This is why tests currently stub the queue — running the real one would delete CWD-relative dirs.
- **Save conflation:** rapid saves of one target are conflated by `CompileScheduler` keyed on
  `displayName` — in-flight compile finishes, only the newest pending survives
  (`ServerImpl.scala:299-307`).
- **CSP has no failure channel.** `CompileOutput` requires `outputJar`; there is no
  success/failure field. `onLogMessage` / `onPublishDiagnostics` / `onTaskProgress` are declared
  in `zincSmithy/smithy/csp.smithy` but **not listed in the `CspClient` service operations**, so
  no client mounts them. Compile-with-errors behavior is currently whatever exception propagation
  does. Fixing this contract is H5.
- **zincCli internals:** per-`scopeId` `Mutex` (`zincCli/src/org/scala/abusers/zincCli/Server.scala:32-49`);
  analysis persisted as `${scopeId}-analysis` jar via `OutputJar` + `ZincCliAnalysisStore`
  (`Server.scala:106-118`); output jar `${scopeId}-classes`. `OutputJar` uses a `temp` dir
  next to the destination (relevant to Z5 atomicity).
- **`workspace/didChangeWatchedFiles` does not exist** in `slsSmithy/smithy/lsp.smithy` or
  `ServerImpl`. 3.7/3.8 are production feature + test, not test-only.
- **Subprocess CSP client pattern** lives in
  `exampleZincCliClient/src/org/scala/abusers/example/Client.scala`: fs2 `Processes.spawn("java", "-jar", serverJar)`,
  `FS2Channel`, `ClientStub(CspServer, channel)`, `lsp.encodeMessages`/`decodeMessages` framing.
  H2 productizes this.

---

# Tier 0 — Harness work items

## H1. Injectable `.sls` root; real `ComputationQueueImpl` in `TestServer`

**Blocks:** 3.5, 3.10; strengthens every Tier-3 test.
**Production change (small, also a real fix):** thread a configurable root through
`ScalaBuildTargetInformation` so `classesDir`/`classJarPath` are workspace-rooted instead of
CWD-`./.sls/`. A long-running server whose output paths depend on process CWD is fragile
independent of tests. Keep the default behavior identical for production (`<workspaceRoot>/.sls/...`).
**Test change:** `TestServer` points the root at the temp workspace and constructs
`ComputationQueue.instance` (the real impl) instead of `TestComputationQueue`. Keep
`TestComputationQueue` available for narrow unit tests, but the default `TestServer.resource`
uses the real queue. Delete the "3.0.0 skips the swap" caveat from `TestServer`'s scaladoc once done.
**Verify:** existing `TestClientSpec` still green; add one assertion that after save→compile the
unzipped classes appear under the temp `.sls` root (proves pending syncs actually ran).

## H2. `CspTestClient` — assertive subprocess client for the real zincCli assembly

**Blocks:** all Tier Z.
**Shape:** new mill module (suggested name `cspIt`) with `forkEnv("SERVER_JAR" -> zincCli.assembly().path)`
following the existing `exampleZincCliClient` wiring in `build.mill`. Inside:

```scala
object CspTestClient {
  final case class Handle(
    server: CspServer[IO],
    notifications: Ref[IO, List[CapturedNotification]], // once H5 mounts them
    kill: IO[Unit],       // destroy the process (for Z5)
  )
  def spawn(serverJar: os.Path): Resource[IO, Handle]
}
```

Port the channel/framing setup from `exampleZincCliClient` verbatim; add: workspace helpers
(temp source dir, write/edit/delete file), and a **clean-compile oracle**:

```scala
object CompileOracle {
  /** Compile `sources` in a fresh scope on a fresh server; return jar entry names -> SHA-256. */
  def cleanOutput(sources: Map[String, String], ...): IO[Map[String, String]]
}
```

Jar comparison = entry names + per-entry content hash; **ignore zip metadata/timestamps**.
TASTy and bytecode from the same compiler version are deterministic; if a nondeterministic entry
surfaces, exclude it explicitly with a comment rather than weakening the comparison.
**Verify:** a smoke test — compile two files, assert the output jar contains their `.tasty`/`.class` entries.

## H3. Watched-files support (production feature + `TestClient` verb)

**Blocks:** 3.7, 3.8.
**Production:** add `workspace/didChangeWatchedFiles` to `lsp.smithy` (op + `FileEvent`/`FileChangeType`
per LSP spec), regenerate, implement in `ServerImpl`. Minimal semantics to start: for changed/created/
deleted files belonging to a known target, schedule a recompile via `CompileScheduler` and reconcile
the index; open buffers keep priority (see 3.8 for the pinned policy).
**Test:** `TestClient.changeWatchedFiles(events*)` verb that fires the notification (after mutating
disk itself, mirroring how `saveFile` writes then notifies).
**Note for the agent:** this is the largest item in the plan; propose the reconciliation design in
the PR description before implementing if anything is ambiguous.

## H4. Convergence oracle helper

**Blocks:** 3.1–3.4, 3.7 assertions (usable by all Tier-3 tests).

```scala
object Convergence {
  /** Spin a fresh TestWorkspace+TestServer over `finalSources`, replay open+save per file,
    * await Ready, and compare: (a) symbolIndex.searchSymbols("") id sets,
    * (b) latestDiagnostics per uri. Returns weaver Expectations with labeled diffs. */
  def check(session: TestServer.Handle, finalSources: Map[String, String]): IO[Expectations]
}
```

The determinism test in `TestClientSpec` already does (a) by hand — refactor it onto this helper.
Diagnostics comparison should normalize ordering and compare as sets.

## H5. CSP failure contract (smithy change + both sides)

**Blocks:** Z2, Z8; needed before diagnostics-based Z assertions.
**Decision to make first (agent: propose, get sign-off):** either
(a) make `CompileOutput.outputJar` optional and add `success: Boolean` + `diagnostics` list, or
(b) keep request/response minimal and mount `onPublishDiagnostics` (typed, not `String`) into the
`CspClient` service so diagnostics stream as notifications, with `CompileOutput` gaining only `success`.
Option (b) matches LSP's push model and what `sls` ultimately needs for build diagnostics; the
current `String` payloads on the three notification ops should become typed structures either way.
**Scope:** `csp.smithy`, `ZincCliServer` (emit result/diagnostics from the zinc `Reporter`),
`sls`'s CSP client handling in `BspStateManager.compileWithCSP` path, `FakeCspServer` (mirror the
new contract), `CspTestClient` (capture notifications).

---

# Tier Z — zincCli/CSP tests (module `cspIt`, real subprocess)

All use `CspTestClient` (H2). Oracle = clean compile of final sources (`CompileOracle`).
These test **our wiring of zinc** (classpath, previous analysis, source sets, stores) — zinc's
invalidation algorithm itself is upstream's problem, but misfeeding zinc is the #1 practical cause
of under-invalidation, and that is 100% ours.

### Z1. Cross-file invalidation matches clean compile
Sources: `A.scala` calling a method from `B.scala`. Compile. Edit B's method signature so A's call
site breaks (or changes bytecode). Recompile.
**Assert:** output jar ≡ oracle on final sources; `changedFiles` includes both B (edited) and A
(invalidated) — i.e. the closure, not just the touched file.
**Prereqs:** H2. **Failure mode prevented:** under-invalidation (green-on-broken; the defining zinc bug class).

### Z2. Error flip: diagnostics match clean compile, then clear
Compile clean → introduce type error → compile → fix → compile.
**Assert:** erroring compile reports failure with diagnostics whose positions/severity equal a
clean compile's diagnostics of the same sources; final compile reports success, no diagnostics,
output ≡ oracle.
**Prereqs:** H2, H5. **Failure mode:** phantom/imprecise compile-server diagnostics.

### Z3. Deletion propagates to output
Compile two files; delete one source; recompile (sourcePath without it).
**Assert:** its `.class`/`.tasty` entries absent from the output jar; output ≡ oracle on remaining source.
**Prereqs:** H2. **Failure mode:** ghost classfiles from deleted sources (classic stale-output bug).

### Z4. Analysis reuse across server restart
Compile N files. Stop server (graceful). Spawn a fresh server, edit one file, recompile with the
same `scopeId`/workdir.
**Assert:** `changedFiles` covers only the edited file's closure (proves the persisted analysis
was loaded, not a silent clean rebuild); output ≡ oracle.
**Prereqs:** H2. **Failure mode:** analysis not durable → every session pays full rebuilds.

### Z5. kill -9 mid-compile never wedges the store
Start a compile of sources made slow enough to reliably interrupt (e.g. many files or a large
generated source — tune once, keep deterministic-ish; the test must tolerate the race in either
direction: killed-before or killed-during is fine, killed-after means retry the setup). `kill`
the process. Spawn fresh server, same workdir, recompile.
**Assert:** compile succeeds. Acceptable outcomes: incremental (analysis intact) or clean rebuild
(analysis discarded); unacceptable: error or hang. Output ≡ oracle.
**Prereqs:** H2. **Failure mode:** Bloop's poisoned-store, "delete `.bloop`" ritual.

### Z6. Corrupt analysis file → clean-compile fallback
Compile once; overwrite the `${scopeId}-analysis` jar with garbage bytes; recompile.
**Assert:** compile succeeds (clean rebuild), a warning is surfaced (log notification once H5
lands; until then, assert non-crash + correct output), and the store is rewritten valid (a third
compile is incremental again).
**Prereqs:** H2. **Failure mode:** crash-loop on unreadable analysis.

### Z7. Same-scope serialized, cross-scope parallel
Fire two concurrent `compile` calls with the same `scopeId` (make the first slow); then two
concurrent calls with distinct scopeIds.
**Assert:** same-scope: both complete, outputs correct, and observable non-overlap (e.g. second
result reflects first's output; simplest robust check: both jars ≡ their oracles and the analysis
store is valid after). Cross-scope: both complete correctly; do **not** assert timing overlap
(flaky) — assert absence of cross-contamination: each jar contains only its own scope's classes.
**Prereqs:** H2. **Pins:** the per-scope `Mutex` design from `Server.scala:40-49` / PR #92.

### Z8. Error-response contract pinned
Compile sources with an error.
**Assert:** exactly what H5's contract promises — success flag false, diagnostics present, jar
field per contract (absent or last-good, whichever H5 chose). This test is the executable spec of
the contract; write it alongside H5.
**Prereqs:** H2, H5.

### Z9. Property: random edit sequences converge (later)
Generator: N rounds of {edit file, add file, delete file, flip error on/off} → compile each round.
**Assert:** final output ≡ oracle. Seeded, with the failing seed printed.
**Prereqs:** Z1–Z6 green first (this test finds bugs; the earlier ones localize them).

---

# Tier 3 — Full-stack LSP tests (`sls.test`, TestServer harness)

All in `sls/test/src/org/scala/abusers/sls/`; suggested suite split:
`DiagnosticsLifecycleSpec` (3.1, 3.2, 3.4), `IndexConvergenceSpec` (3.3, 3.9), `ConcurrencySpec`
(3.5, 3.6, 3.10), `WatchedFilesSpec` (3.7, 3.8), `HostileInputSpec` (3.11), `RecoverySpec` (3.12, 3.13).
Await via `awaitUntil`; converge via `Convergence.check` (H4).

### 3.1. Diagnostics appear then clear
Open file, insert a type error at a marker, save → await diagnostics non-empty for that uri.
Fix the error (edit back), save → await diagnostics empty.
**Assert:** both transitions; final `Convergence.check`.
**Prereqs:** H4. Note: with the stub PC disabled, diagnostics come from the CSP/compile path —
if `FakeCspServer` doesn't yet surface diagnostics, this test drives a small extension to it
(mirror whatever H5 defines; coordinate).
**Failure mode:** the single most-reported Metals bug — stale red squiggles.

### 3.2. Failed compile preserves last good state
Open + save a clean file (symbol `foo` indexed). Edit to introduce a compile error + rename `foo`→`bar`, save.
**Assert:** compile fails; index still contains `foo`, not `bar` (no half-applied update). Fix the
error keeping `bar`, save → index has `bar`, not `foo`; `Convergence.check`.
**Prereqs:** H4, and FakeCspServer must fail the IO on compile errors (it already throws).
**Failure mode:** mixed old/new state after partial failure — the root of "restart to fix".

### 3.3. Deletion propagates to the index
Open file with two defs, save. Delete one def, save.
**Assert:** deleted symbol absent from `symbolIndex`, other present; `Convergence.check`.
**Failure mode:** additive-only indexes that accrete ghosts. Deletion is where staleness hides.

### 3.4. Cross-file staleness (flagship)
Workspace: `A.scala` uses `B.scala`'s API. Open both, save (clean). Edit B's signature so A breaks,
save B only.
**Assert:** diagnostics appear **for A** (a file the user didn't touch); fix A, save → all clear;
`Convergence.check`.
**Prereqs:** H4; benefits from real-CSP `changedFiles` — with `FakeCspServer` (full recompile every
time) this passes trivially, so also add a variant wired with the real zincCli via the existing
`TestServer.resource(ws, cspServer = Some(...))` override once H2 exists. The stub variant still
guards the LSP-side plumbing.
**Failure mode:** Metals' weakest spot — cross-file staleness.

### 3.5. Swap-window safety
With the real queue (H1): make the CSP stub's compile controllable (a `Deferred` gate). Save →
compile completes → pending sync (delete+unzip) is queued. Before any further `synchronously`
computation, fire a request that reads the classes dir state.
**Assert:** the pending sync runs **before** the queued request executes (observable: request sees
the fully unzipped dir, never mid-delete). Also assert the swap actually happened (new classfile present).
**Prereqs:** H1. **Failure mode:** category-B race that generates the exact stale-PC-classpath
window Metals users hit after every compile.

### 3.6. Save conflation: newest wins, none lost
Gate `FakeCspServer.compile` on a `Deferred` so the first compile blocks. Fire 5 edit+save rounds
(contents v1..v5) while blocked. Release.
**Assert:** exactly the semantics of `ServerImpl.scala:299-307` — in-flight (v1) completes,
intermediate saves dropped, final compiled state is v5; count compile invocations ≤ 2 after release;
`Convergence.check` against v5.
**Prereqs:** H1 preferred (real queue), controllable stub.
**Failure mode:** lost updates under typing bursts — B-category.

### 3.7. Branch switch converges
Workspace with 3 files, opened+saved once, then all closed. Rewrite all 3 on disk (changed
signatures, one file deleted, one added), fire `changeWatchedFiles` with the corresponding events.
No `didOpen`.
**Assert:** `Convergence.check` against the new disk state (index reflects new/removed symbols;
stale diagnostics for the deleted file cleared).
**Prereqs:** H3, H4. **Failure mode:** C-category, the scenario Metals fumbles most visibly.
This is the "SLS gets it right" demo test.

### 3.8. External change under an open buffer — pin the policy
Open a file (buffer v1). Change it on disk externally + `changeWatchedFiles`.
**Policy to pin (confirm with maintainer if H3's design didn't already):** open buffer wins —
PC/index views of *that uri* keep buffer content; disk change takes effect on close-without-save
or next open.
**Assert:** whatever the pinned policy is, exactly; add a comment marking it as a deliberate
product decision.
**Prereqs:** H3. **Failure mode:** undefined behavior on checkout-while-editing.

### 3.9. didClose discards the buffer
Open, edit (don't save), close.
**Assert:** subsequent state (index after a watched-file-triggered or fresh-open pass) reflects
disk, not the abandoned edit; buffer map no longer holds the uri.
**Failure mode:** zombie buffers shadowing disk.

### 3.10. Version freshness under didChange bursts
With the real queue (H1): send k sequential `didChange`s (versions 1..k) immediately followed by a
request that reads document state (any op that goes through `synchronously` and touches
`TextDocumentSyncManager`).
**Assert:** the request observed version k's content — assert on content/version, never on timing.
**Prereqs:** H1. **Failure mode:** stale-version responses — completions landing at wrong offsets.

### 3.11. Hostile input table
Table-driven over one open workspace: negative position; range end < start; range past EOF;
`didChange` on a never-opened uri; unknown uri scheme; `didClose` twice.
**Assert:** each returns an error (or is safely ignored where the LSP spec says so) within a
timeout; server still answers a well-formed request afterward; no diagnostics/state corruption
(`Convergence.check` at the end).
**Failure mode:** D-category — a hostile/buggy client wedging the server.

### 3.12. CSP subprocess death recovery
Layer-3 wiring with the real zincCli (via the `cspServer` override + H2's spawn/kill): save once
(healthy), kill the CSP process, save again.
**Assert:** the second save surfaces an error to the client (log/show message) instead of hanging;
after respawn/recovery (whatever `ResourceSupervisor`/reinit provides — if nothing does yet, this
test drives that decision), a third save succeeds and converges.
**Prereqs:** H2. **Failure mode:** Bloop-style silent death.

### 3.13. BSP session death → reinitialize
Thin test: make the BSP stub start failing (flip a `Ref`), trigger the path that talks to BSP
(open a file in an unknown target), assert a clean error surfaces; then reinitialize and assert
recovery. Keep minimal — Mill-BSP is structure-only and rarely exercised.
**Failure mode:** unrecoverable session after build-tool restart.

---

# Tier PC — presentation-compiler plumbing (blocked)

**Blocker:** loading a real `PresentationCompilerProvider` under mill's test-runner classloader
breaks on scache's background machinery (noted in `TestServer.scala`). Resolve first — options:
fork the test JVM for these suites, or load the PC for the test's own Scala version in-process.
PC *semantics* (completion contents, hover text) are tested upstream in the compiler repo — these
tests assert only SLS's plumbing, so assertions stay shallow ("contains `baz`"), never golden.

### P1. Buffer freshness
Open file, `didChange` adding `def freshMethod`, **no save**. Completion at a marker after the
receiver.
**Assert:** `freshMethod` in results (PC saw the buffer, not disk).

### P2. Classpath swap visibility
Two files. Add a public def to A, save (compile + swap runs). Completion in B at a marker.
**Assert:** the new symbol appears — this one assertion covers the `.sls` jar prepend,
`invalidateCompilers()`, and doc sync end-to-end.
**Prereqs:** H1 (real queue so the swap actually runs).

### P3. Cancellation
Hook a PC stub (or slow real request) gated on a `Deferred`; fire request, then `$/cancelRequest`
via `IOCancelTokens`.
**Assert:** the request terminates cancelled (not after completing), queue drains, next request fine.

### P4. Position round-trip
Marker-pinned request whose response carries positions (e.g. definition/hover range).
**Assert:** returned `lsp.Position` equals the marker — guards the Chimney lsp4j↔lsp conversions
against off-by-one/UTF-16 drift.

---

# Tier I — index gaps (existing harness)

### I1. Crash containment
`TestWorkspace` with N healthy sources + one indexer-hostile input (reuse the crash fixtures from
`ai/tasty-indexer-crashes.md` work / `TastyIndexerSpec`).
**Assert:** all healthy files indexed; exactly one error surfaced; `IndexLifecycle` stays `Ready`.
**Failure mode:** one bad file killing features workspace-wide.

### I2. Fixture JARs stay (no code change)
Policy, recorded here: the frozen pre-compiled fixture JARs are **not** superseded by
`TestWorkspace` — they are the only coverage for TASTy produced by *other* compiler versions
(version-drift crashes). Do not delete them; add new versions' fixtures when supporting new Scala versions.

---

# Implementation order

Each step is mergeable alone; verify with `./mill __.compile && ./mill sls.test` (+ new module's test where applicable).

1. **H1** → **3.1, 3.2, 3.3, 3.6** — highest value, harness nearly ready. (H4 falls out of 3.1's refactor.)
2. **H4** (formalize) → **3.4** (stub variant) — the flagship.
3. **H2** → **Z1, Z3, Z4** + smoke — core compile-server correctness.
4. **H5** → **Z2, Z8** (+ upgrade 3.1 to typed diagnostics) — the failure contract.
5. **3.5, 3.10, 3.11** — concurrency + armor.
6. **Z5, Z6, Z7, 3.12, 3.13** — durability/lifecycle. 3.4 real-CSP variant lands here too.
7. **H3** → **3.7, 3.8, 3.9** — external changes.
8. **PC tier** once the scache/classloader blocker is resolved; **Z9** last.

# Out of scope (deliberately)

- PC semantics (upstream: scala/scala3), zinc's invalidation algorithm (upstream: sbt/zinc),
  compiler behavior (AGENTS.md §5).
- Bloop anything — not in the architecture.
- Cold-start/partial-service tests (E) — blocked on a product decision about serving during indexing.
- Performance benchmarks — separate concern; Z4's "only the closure recompiled" is the one perf-ish
  assertion we keep because it's a correctness signal for analysis reuse.

# Small fixes bundled with this plan

- `applyEdit` in `TestClient` counts UTF-16 naively — add a guard/comment (ASCII-only test sources)
  so a future emoji in a fixture doesn't silently misalign edits.
- Update `test-infrastructure.md`: mark Proposals 2–4 implemented, delete the stale
  `architecture-improvements.md:330` reference, point its phasing section at this document.
