# Progress & status system: surfacing work and failures to the user

## Context

SLS must never fail silently — the recurring complaint about other tools. Today the user-facing
channels are thin and the structured signal we *do* have is invisible to users:

- The smithy client service (`slsSmithy/smithy/lsp.smithy`) exposes only `window/logMessage`,
  `window/showMessage`, and `textDocument/publishDiagnostics`. No `$/progress`, no status item.
- `IndexLifecycle` is a single coarse `Cold → Bootstrapping → Ready → Failed` `SignallingRef`.
  `Failed` is binary: one library failing to index is either swallowed (`indexJarSafely` logs and
  moves on) or, if it escaped, would nuke the whole phase. There is no per-item detail.
- `IndexManager.notifyProgress` just `toString`s into `window/logMessage`.
- otel `Tracer[IO]` is threaded through `ServerImpl`/`IndexManager`, but it is a dev/ops sink
  (external backend) — users never see it.

The seed idea was "an Index trace that stores each library as a span." That generalizes into a
single status model with many projections.

---

## Core idea: one status model, many sinks

Model long-running or fallible work as an **Activity** (≈ a span); children link to a parent to form
a tree (≈ a trace). Decouple the *model* from the *transports*. The LSP progress bar, a status-bar
item, a drill-down document, and the otel trace are all **projections of the same tree**, so they
can't drift.

### The model — `Activity`

```
Activity:
  id, parent              // tree → "trace"
  kind: Index | Compile | Bsp | Pc | Resolve   // extensible enum
  label                   // "Index bootstrap", "cats-core_3-2.13.0.jar", "Compile sls"
  severity: Info | Warning | Error             // how loud, orthogonal to status
  status: Pending | Running | Succeeded | Skipped | Failed(message, cause)
  detail, startedAt, endedAt
```

Two deliberate separations:

- **status vs severity.** A library that fails to index is `Failed` + `Warning` (degraded, the
  server lives); a failed compile is `Failed` + `Error`. Sinks route on severity (log vs toast).
- **Rollup.** A parent reflects its children: `Index bootstrap` becomes "Ready — 3 of 200 libraries
  failed" instead of a silent drop or a hard `Failed`. This rollup is the anti-silent-failure win.

### The hub — `StatusManager` (single funnel)

Owns the tree (`Ref[IO, Map[ActivityId, Activity]]`) plus an event `Topic[IO, Activity]`. API:

```
start(kind, label, parent, severity): IO[ActivityId]
succeed(id, detail) / skip(id, reason) / fail(id, cause, message): IO[Unit]
scoped(kind, label, parent)(use: ActivityId => IO[A]): IO[A]   // guaranteeCase wrapper
snapshot: IO[Vector[Activity]]
changes: Stream[IO, Activity]
```

**Every fallible or long-running operation reports here.** That single rule is what kills silent
failures. A new subsystem just emits Activities; it doesn't know who's listening.

### The sinks — independent subscribers

| Sink | Channel | Behaviour |
|---|---|---|
| **Log** | `window/logMessage` | every transition, full detail (works today, no protocol change) |
| **Progress** | `$/progress` + `window/workDoneProgress/create` | Running roots → live bar: "Indexing 45/200 — cats-core" |
| **Notification** | `window/showMessage(Request)` | only `Error` / aggregated `Warning`; debounced/rolled up → one "Indexing finished, 3 errors" toast, never 50 |
| **Status item** | custom `sls/status` notification | status-bar: Ready ✓ / Indexing ⟳ / Degraded ⚠ (N). Click → `sls/showStatus` |
| **otel** | `Tracer` | Activity → span, same parent/child + status. The dev trace is *derived from* the same model |

Drill-down: `sls/showStatus` (a `workspace/executeCommand`) renders the tree to a markdown virtual
document via `window/showDocument`, failures expanded with the real cause
(e.g. "guava-32.jar: failed to read TASTy — NoSuchFileException").

### otel mapping (the trace sink)

`Tracer[IO]` is already wired, so spans are nearly free and make the user-facing tree and the otel
trace one structure:

- `start` → `Tracer[IO].spanBuilder(label).withParent(parentCtx).build.startUnmanaged`, stash the
  `Span[IO]` by `ActivityId` (`startUnmanaged` because our lifecycle is imperative start/end).
- `fail` → `span.recordException(cause) *> span.setStatus(Error) *> span.end`; `succeed` →
  `setStatus(Ok).end`.
- Parent/child via the **stored `SpanContext`**, passed explicitly — sinks run on separate fibers,
  so we cannot rely on fiber-local context propagation for the general case.

(Note: when the *producer's own work* runs inside a span scope — as in the slice below — fiber-local
propagation already nests children correctly without the manual `SpanContext` plumbing. The manual
path is only needed when the otel sink reconstructs spans off the `changes` stream.)

---

## Why it scales

The same machinery later surfaces, with no new infra:

- **Compile failure** — `Compile` activity, `Error`, links the target (currently `didSave` compile
  errors are swallowed in `ServerImpl.handleDidSave`).
- **BSP/CSP disconnect** — `Bsp` activity with a "Reconnect" action via `showMessageRequest`.
- **PC load failure per Scala version** — `Pc` activity, `Warning` (that version's editor features
  degrade, the server lives).

`IndexLifecycle` then becomes a *projection* of the Index root activity's rollup rather than a
parallel state machine.

---

## Decisions (made during design)

- **Surface: full** — add `$/progress` AND a custom `sls/status` item + `sls/showStatus` drill-down,
  not just standard progress.
- **First slice: index only, end to end.**
- **Client: server-side first.** Land the protocol + server (progress works in any client; `sls/status`
  + `sls/showStatus` are emitted and tested at the wire level); wire the actual status-bar item in the
  editor client as a follow-up.
- **Trace sink: include from day one** (Tracer already wired).

## Protocol additions (all shapes already exist in `lsp-smithy-definitions` 0.2.0)

Only operations need wiring — no new shape authoring:

- `SlsLanguageClient` += `lsp#Progress` (`$/progress`), `lsp#WindowWorkDoneProgressCreate`,
  `lsp#WindowShowMessageRequestOp`, `lsp#WindowShowDocument`.
- `SlsLanguageServer` += `lsp#WorkspaceExecuteCommandOp` (for `sls/showStatus`).
- One tiny custom shape for the status-item notification: `sls/status → StatusParams{ state, label, detail }`.

## Rollout (each step independently shippable)

1. `Activity` model + `StatusManager` (no protocol change, unit-testable) — the keystone.
2. Sinks: Log + otel (no protocol change), then Progress, then Status item.
3. smithy operations.
4. Index integration: `IndexManager.bootstrap` root activity, per-library children, rollup;
   `IndexLifecycle` becomes a projection; delete `notifyProgress`.
5. TestClient capture (progress + `sls/status`) + tests: a failing library → `Degraded` rollup + a
   captured failure; progress begins/ends.

---

## Implemented so far

Only the otel spans — the minimal, immediately-useful piece (`IndexManager`):

- `index.bootstrap` — root span of the index trace (attribute: `index.targets`).
- `index.jar <library>` — one span per library under `indexJarSafely`, **named after the jar**
  (e.g. `index.jar cats-core_3-2.13.0.jar`) so the trace tree is scannable; the full path is also on
  the `index.jar` attribute. Nested under the root via fiber-local context propagation (the
  `parEvalMapUnordered` fibers inherit the bootstrap scope). On a hard crash the exception is recorded
  on the span (`recordException`) and the status set to `Error`; the failure is still swallowed (a bad
  jar degrades that library, it doesn't fail the bootstrap). Attribute `index.symbols` records how
  many symbols the jar contributed.

- **Partial-failure capture (the "indexed but with errors" case).** Reading a jar's TASTy against a
  slightly-wrong classpath makes dotc emit per-file typer errors *but keep going*, so the jar indexes
  partially and used to look like a clean success while the errors spewed to stderr. Now
  `TastyInspectorDriver` installs a `CollectingReporter` (a dotc `Reporter`) passed to
  `Driver.process(args, reporter, …)` — which (a) **stops the stderr spew** and (b) hands the
  diagnostics back as `List[IndexDiagnostic]`. `TastyIndexer.attachDiagnostics` records them on the
  *current* span (`Tracer.currentSpanOrNoop`, i.e. the `index.jar` span): `index.errors` /
  `index.warnings` counts, the first 20 messages as `index.error`/`index.warning` span events, and
  `setStatus(Error)` when any error was seen — so a partially-indexed jar stands out in the trace
  instead of masquerading as clean. Also logged (`logger.warn`) so the signal survives with no trace
  backend attached. `TastyIndexer` / `SymbolIndexer.tasty` now take `(using Tracer[IO])`.

  Open follow-up (deliberately deferred): whether `index.errors > 0` should *trigger the bytecode
  fallback* for that jar (partial TASTy may be worse than complete bytecode) — that's a strategy
  decision in `runStrategy`, not just surfacing.

`IndexManager` now takes `(using Tracer[IO])`; prod/integration wiring supplies the real tracer,
`IndexManagerSpec` supplies `Tracer.noop[IO]`.

**Not yet built:** `StatusManager`, the Activity model, the non-otel sinks, the protocol operations,
and the `IndexLifecycle`-as-projection change. This document is the plan of record for them.
