# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / test commands

The build is Mill 1.1.6 (Scala 3.8.3). Use the repo-local launcher `./mill`. CI runs `./mill _.test`.

- Run all tests: `./mill _.test`
- Run a single module's tests: `./mill sls.test`
- Run one Weaver suite or test: `./mill sls.test --only 'org.scala.abusers.sls.TextDocumentSyncSuite'` (Weaver via `weaver.framework.CatsEffect`)
- Launch the LSP from sources: `./mill sls.run`
- Build the Zinc CLI fat jar: `./mill zincCli.assembly`
- Compile everything: `./mill __.compile`
- Scalafix (RemoveUnused): `./mill __.fix` — scalac flag `-Wunused:all` is on for every module
- Format: `scalafmt` (config in `.scalafmt.conf`, version 3.11.0). The nix devshell wires `treefmt` for both scalafmt and nixpkgs-fmt.

JDK: CI uses 17; the nix flake provides JDK 21. Both work.

The `exampleZincCliClient` module relies on `forkEnv` (`SERVER_JAR`, `TEST_SOURCES`, `TEST_CLASSPATH`, `TEST_SCALA_VERSION`) that Mill computes from `zincCli.assembly` and `testModule`. Run it via Mill (`./mill exampleZincCliClient.run`) rather than `java -jar`, or these env vars will be missing.

Profiling/tracing is opt-in via `SLS_PROFILING=true` (or `-Dsls.profiling=true`). When off, `ProfilingIOApp` is a plain `IOApp`. `langoustine-tracer` is the local launcher for inspecting LSP traffic — see `README.md`.

## Architecture

This repo hosts two cooperating servers plus the Smithy contracts that define their wire protocols.

### Modules (defined in `build.mill`)

- `slsSmithy` — Smithy IDL for LSP (`slsSmithy/smithy/lsp.smithy`). Generates `SlsLanguageServer[F]` and `SlsLanguageClient[F]` via the smithy4s mill plugin. Depends on `lsp-smithy-definitions` (external LSP-as-Smithy package) and `jsonrpclib-smithy`.
- `zincSmithy` — Smithy IDL for **CSP** (Compile Server Protocol, `zincSmithy/smithy/csp.smithy`). Generates `CspServer[F]` / `CspClient[F]`. CSP is a project-local protocol, not a standard.
- `sls` — the language server. Depends on `slsSmithy`, `profilingRuntime`, and `zincCli`. Main: `org.scala.abusers.sls.SimpleScalaServer`.
- `zincCli` — standalone Zinc-based incremental compile server speaking CSP over stdio JSON-RPC. Main: `org.scala.abusers.zincCli.ZincCli`. Designed to be run as a separate JVM (hence `assembly`).
- `exampleZincCliClient` — reference client that spawns `zincCli` and drives it. Useful when iterating on CSP without the full LSP.
- `profilingRuntime` — reusable `ProfilingIOApp` (Cats Effect IOApp + Pyroscope agent + otel4s SDK + IORuntime metrics). Both `sls` and any future server should extend this rather than plain `IOApp` to inherit telemetry.
- `testModule` — a small Scala 3 sources fixture compiled and fed to `exampleZincCliClient` at runtime.

### How sls works at runtime

`SimpleScalaServer.program` constructs a single `FS2Channel` over stdin/stdout, then wires `ServerImpl` as the endpoint set. The same channel is used to issue notifications/requests to the client via a smithy4s `ClientStub` for `SlsLanguageClient`.

`ServerImpl` is assembled from these collaborators (see `SimpleScalaServer.server`):

- `PresentationCompilerProvider` — loads Metals' `RawPresentationCompiler` per Scala version via service loader / classloader isolation (see `sls/pc/`). PC interop types live in `PresentationCompilerDTOInterop`.
- `BspStateManager` + `BuildServer` — the LSP initializes a BSP session (Mill → Bloop) and tracks per-source build targets, classpaths, and scalac options. `BuildServer.suspend` wraps a `Deferred[IO, BuildServer]` so endpoints can be wired before the connection completes.
- `CspServer[IO]` — connection to the spawned `zincCli` process, used for actual compilation. Like BSP, it's deferred and suspended until ready.
- `TextDocumentSyncManager` — in-memory document state, applied via LSP `didChange` events.
- `DiagnosticManager` — accumulates diagnostics for `textDocument/publishDiagnostics`.
- `IOCancelTokens` — converts LSP `$/cancelRequest` into presentation-compiler `CancelToken`s. `LSPCancelRequest.cancelTemplate` is what wires this into the FS2 channel.
- `ComputationQueue` — serializes LSP requests onto a synchronized state token (`SynchronizedState`). Every LSP entry point in `ServerImpl` (`textDocumentDidChange`, `textDocumentCompletionOp`, etc.) goes through `computationQueue.synchronously { ... }`. Tests stub this with a passthrough implementation.
- `ResourceSupervisor` — tracks long-lived child resources (BSP/CSP client processes) so they get released on shutdown or reinitialization.

The Smithy-generated server interface is `SlsLanguageServer[IO]`; `ServerImpl` implements it directly. Adding a new LSP operation means: add the op to `lsp.smithy`, regenerate (happens on `compile`), then implement the new method.

### Chimney for DTO interop

The Metals presentation compiler returns `org.eclipse.lsp4j.*` types; the smithy-generated wire types are `lsp.*`. Conversions use `io.scalaland.chimney` (often via the `convert[lsp4j.X, lsp.Y]` helper in `PresentationCompilerDTOInterop`). `chimney-java-collections` is on the classpath for `java.util` ↔ Scala collection bridging. Prefer extending the Chimney transformers over hand-written conversion.

### LSP ↔ CSP ↔ BSP split

- BSP (Bloop) is the source of truth for **build structure**: what targets exist, their classpath, sources, scalac options. Driven from `ServerImpl.initializeOp`.
- CSP (zincCli) is the source of truth for **incremental compilation output**: it owns the Zinc analysis store and produces the per-target jar that LSP feeds into the presentation compiler. `ScalaBuildTargetInformation.classpath` prepends `.sls/classes/<target>/` and `.sls/classes/<target>.jar` so the PC sees freshly compiled code.
- LSP (sls) is the user-facing protocol. It does not itself invoke scalac — it asks BSP for structure and CSP for compilation, and uses the presentation compiler only for editor features (completion, hover, definition, signature help, inlay hints).

When changing compile-output paths or BSP/CSP boundaries, keep all three in sync: the PC classpath in `BspStateManager`/`ScalaBuildTargetInformation`, the CSP `CompileOutput.outputJar`, and any client expectations.

## Conventions

- Scala 3 with significant indentation **disabled** (`runner.dialectOverride.allowSignificantIndentation = false`) — use braces.
- `maxColumn = 120`, `trailingCommas = multiple`, `defaultWithAlign`. Imports are auto-organized into two groups (`[a-z].*` then `java.*` / `scala.*`).
- `-Wunused:all` is on and Scalafix runs `RemoveUnused` — unused symbols will fail the build under `./mill __.fix`.
- All effectful code is `cats.effect.IO`; streaming is `fs2`; JSON-RPC is `jsonrpclib-fs2`.
