# In-editor trace/telemetry inspector for SLS (langoustine-style)

> Status: **design only, not implemented.** Plan of record for when we pick this up. The user works
> with Grafana for now.

## Context

SLS already emits otel spans for every LSP op (`op(name)` in `ServerImpl`) and for indexing
(`index.bootstrap`, `index.jar <library>`, with attributes/events incl. captured exceptions). Today the
only way to see them as traces is Grafana (OTLP export) — out of editor, needs a collector. The user
already runs **langoustine-tracer** (which wraps the server in `slc`'s run mode and shows LSP JSON-RPC
traffic in a Scala.js/Laminar web UI on a random port), but it knows nothing about *our* otel spans +
metadata. The goal: a **langoustine-style inspector for our telemetry** — spans, their attributes/events,
and trace waterfalls — viewable inside VS Code.

Decisions made with the user:
- **Deliver via an embedded HTTP server in SLS** (the langoustine model), not a VS Code-native webview app.
- **Frontend in Scala.js + Laminar** — exactly what langoustine-tracer uses; all-Scala, served by http4s.
- **Integrate through the existing `slc` extension** (`/home/rochala/Projects/sls/slc`): a thin command opens the dashboard URL (it already has the `sls/debugIndex` command + launches via `langoustine-tracer`).
- Self-contained: no external collector; OTLP→Grafana export keeps working in parallel (we *add* a span processor).
- Related to but separate from `ai/progress-system.md` (user-facing StatusManager) — this is a dev tool.

## Verified facts
- **langoustine-tracer** = Scala.js + **Laminar** frontend + embedded HTTP server, binds a random port (neandertech.github.io/langoustine/tracer.html). This is the model to mirror.
- **otel pipeline** (`profilingRuntime/.../ProfilingIOApp.scala`): `OpenTelemetrySdk.autoConfigured` with `.addTracerProviderCustomizer(_.addSpanProcessor(new ProfilingSpanProcessor))`. `ProfilingSpanProcessor.onEnd` is a **no-op** — the capture hook. otel4s 0.13.1: `SpanData` exposes name/spanContext(`traceIdHex`/`spanIdHex`)/parentSpanContext/start+endTimestamp(`FiniteDuration`)/status(`StatusData` w/ `.description`)/attributes/events(`.elements`).
- **http4s/circe NOT on classpath yet** — must be added. **logback.xml is FileAppender-only** (no stdout appender) — http4s SLF4J logs won't corrupt the JSON-RPC pipe.
- **`slc`** = thin TS extension (single `client/src/extension.ts`, plain `tsc`, `vscode-languageclient@9`, no bundler/webviews). Launches server via `../sls/langoustine-tracer <jar>` (run) or `java -D... -jar <jar>` (debug). Already registers the `sls/debugIndex` command + a `TextDocumentContentProvider` virtual doc.
- **Mill 1.1.6** supports Scala.js via `mill.scalajslib.ScalaJSModule` (`scalaJSVersion`, `fastLinkJS`/`fullLinkJS`). VERIFY exact task/report API + a Laminar version for Scala 3 + circe Scala.js artifact during implementation.

## Approach — four pieces

### 1. Span capture (in `profilingRuntime`)
- `TraceBuffer.scala`: serializable model `TraceSpan(traceId, spanId, parentSpanId: Option, name, kind, startNanos, endNanos, statusCode, statusDescription, attributes: Map[String,String], events: List[TraceEvent(name, tsNanos, attributes)])`; pure `fromSpanData(sd)` (unit-testable) using the verified accessors (`attrs.map(a => a.key.name -> a.value.toString).toMap`, match on `StatusData` subtypes). Holder = drop-oldest ring `Ref[IO, Vector[TraceSpan]]` (cap ~2000) + `fs2.concurrent.Topic[IO, TraceSpan]` for live SSE; `add` updates ring + `topic.publish1` (non-blocking, safe on the `onEnd` hot path); `snapshot`.
- `TraceBufferSpanProcessor(buffer) extends SpanProcessor[IO]`: `onEnd = sd => buffer.add(TraceSpan.fromSpanData(sd))`; others no-op. Composed by a second `builder.addSpanProcessor(...)` call (keep `ProfilingSpanProcessor`).

### 2. Backend HTTP server (in `profilingRuntime`)
- `TraceViewerServer.resource(buffer, host, port): Resource[IO, Server]` via `EmberServerBuilder`, bound **127.0.0.1 only**. Routes:
  - `GET /api/traces` → `Ok(buffer.snapshot)` JSON (circe; flat list, client groups by traceId).
  - `GET /api/stream` → SSE from `buffer.topic.subscribe(256)` + 15s keepalive comment.
  - `GET /` and `GET /assets/*` → serve `index.html` + the linked Scala.js bundle from **classpath resources** (`trace-ui/`), so it ships in the `sls` assembly jar.
- Port from `SLS_TRACE_UI_PORT` (default **8682**); print `http://127.0.0.1:<port>` to **stderr** (`Console[IO].errorln`). circe `deriveEncoder` for the model.
- build.mill: add `http4s-ember-server`, `http4s-dsl`, `http4s-circe` (0.23.30), `circe-core`/`circe-generic` (0.14.14), explicit `ip4s-core` to `profilingRuntime.mvnDeps`. (Accept the published-artifact weight; split to a `traceViewer` module later if it matters.)

### 3. Frontend — new Scala.js module `traceUi` (Laminar)
- New `object traceUi extends ScalaJSModule` in build.mill: `scalaJSVersion`, deps `com.raquo::laminar::<ver>` + `io.circe::circe-core::<ver>` (+ `circe-parser`) Scala.js artifacts. Scala 3.
- Laminar app (`traceUi/src/.../Main.scala`): on load `fetch('/api/traces')`; live via `new EventSource('/api/stream')` (small Scala.js facade or `org.scalajs.dom.EventSource`). State in `Var`/`Signal`. Render, langoustine-style:
  - **Left: trace list** (group by `traceId`, root span name + total duration + span count, newest first).
  - **Right: waterfall** for the selected trace — build the tree from `parentSpanId`, one row per span indented by depth, a bar `left=(start-t0)/T`, `width=(end-start)/T`; red when `statusCode=="ERROR"`.
  - **Detail panel** on span click: attributes table + events (the `exception` event shows `exception.message`/`exception.stacktrace` — the "metadata").
- **Model sharing**: v1 = a frontend-local `TraceSpan`/`TraceEvent` + circe decoder matching the server JSON (~10 lines dup). Refinement: extract a cross-built (`JVM`+`JS`) `traceModel` module as the single source of truth.

### 4. Wiring & gating
- `ProfilingIOAppSettings.traceUiEnabled` (env `SLS_TRACE_UI` / `-Dsls.trace.ui`), independent of `SLS_PROFILING`.
- `ProfilingIOApp.run` (the one ordering constraint): create `TraceBuffer` **first**, then build the SDK resource whose customizer closes over it and (gated) adds `TraceBufferSpanProcessor`; (gated) acquire `TraceViewerServer.resource(buffer, host"127.0.0.1", port)` as another `Resource` step in the same `use`. Off → byte-for-byte current behavior, zero overhead.
- **build.mill bundling task**: a task on the server module that runs `traceUi.fullLinkJS` and copies the linked `main.js` (+ map) into `profilingRuntime` resources `trace-ui/` next to a static `index.html`, so the assembly contains the bundle. (This build step is the main risk — verify Mill 1.x `fullLinkJS` output/report shape early.)
- **`slc`** (`client/src/extension.ts`): add command `sls.openTraceInspector` → open `http://127.0.0.1:8682` via `commands.executeCommand('simpleBrowser.show', url)` (fallback `env.openExternal`). Register in `package.json` `contributes.commands`. Enable the flag in the launched server: add `-Dsls.trace.ui=true` to the debug `args` and/or set `SLS_TRACE_UI` in the run env (langoustine-tracer inherits env). Port is the fixed default (optionally a `contributes.configuration` setting mirrored to `SLS_TRACE_UI_PORT`).

## Pitfalls
- **stdout = JSON-RPC pipe**: http4s logs via SLF4J→logback (FileAppenders only — verified); URL to stderr; audit new files for `println`/`System.out`.
- **Assembly must contain the JS bundle** — the build task ordering (link Scala.js → copy into JVM resources → assembly) is load-bearing; verify in a clean `./mill sls.assembly`.
- In-process buffer only captures this JVM's spans (all `index.*` + LSP `op(...)`; the `zincCli` subprocess won't appear — out of scope).
- Memory bounded by ring cap + `subscribe(256)` backpressure. OTLP export unaffected (added, not replaced).
- Port coordination: fixed default 8682 shared by server + `slc`; make it a setting if needed.

## Critical files
- Edit: `build.mill` (deps + new `traceUi` ScalaJSModule + JS-bundling task), `profilingRuntime/.../ProfilingIOApp.scala` (+ `ProfilingIOAppSettings`), `slc/client/src/extension.ts` + `slc/package.json`.
- New: `profilingRuntime/.../TraceBuffer.scala`, `TraceViewerServer.scala`, `profilingRuntime/resources/trace-ui/index.html`; `traceUi/src/.../Main.scala` (Laminar).
- Reference: `profilingRuntime/.../ProfilingSpanProcessor.scala` (style), `sls/resources/logback.xml` (no stdout appender), `slc/client/src/extension.ts` (`sls/debugIndex` command precedent).

## Verification
**Build:** `./mill traceUi.fullLinkJS` then `./mill sls.assembly`; confirm the bundle lands in resources and the jar.
**E2E:** launch with `SLS_TRACE_UI=true` (via `slc` debug args or `./mill sls.run`); confirm `http://127.0.0.1:8682` prints to **stderr** and stdout stays pure JSON-RPC (editor + langoustine-tracer still clean). Run `slc`'s `SLS: Open Trace Inspector` → Simple Browser shows the inspector. Open a Scala file (→ `index.bootstrap`) and request completion/inlay-hints (→ `op` spans); confirm the nested waterfall, and that the known inlay-hint crash shows a **red** span with its `exception` event (message + stacktrace) in the detail panel. Confirm SSE live-streams new spans without reload, and that running without the flag changes nothing.
**Unit (Weaver, `profilingRuntime/test`):** `TraceSpan.fromSpanData` mapping (incl. `StatusData.Error`→`"ERROR"`+description, attribute/event flattening) from a hand-built `SpanData`; `TraceBuffer` ring-cap drop-oldest. (No compiler/type-system tests, per project rules.)
