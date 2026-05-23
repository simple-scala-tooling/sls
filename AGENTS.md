# AGENTS.md

This file provides guidance to AI agents when working with code in this repository.

## Build / test commands

The build is Mill 1.1.6 (Scala 3.8.3). Use the repo-local launcher `./mill`. CI runs `./mill _.test`.

- Run all tests: `./mill _.test`
- Run a single module's tests: `./mill sls.test`
- Run one Weaver suite or test: `./mill sls.test --only 'org.scala.abusers.sls.TextDocumentSyncSuite'` (Weaver via `weaver.framework.CatsEffect`)
- Launch the LSP from sources: `./mill sls.run`
- Build the Zinc CLI fat jar: `./mill zincCli.assembly`
- Build the SLS fat jar: `./mill sls.assembly` (output: `out/sls/assembly.dest/out.jar`)
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

### Request flow

```
LSP Client → (JSON-RPC/stdio) → SimpleLanguageServer → ServerImpl
  ServerImpl uses:
    ├─ PresentationCompilerProvider (completions, hover, definitions, etc.)
    ├─ BspStateManager (talks to Mill via BSP for build info)
    ├─ CspClient (talks to zinc-cli subprocess for incremental compilation)
    ├─ TextDocumentSyncManager (open file buffers)
    └─ DiagnosticManager (error/warning publishing)
```

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

## Testing

Tests use **Weaver-Cats** (`SimpleIOSuite`). Test sources are under `<module>/test/src/`.

## Conventions

- Scala 3 with significant indentation **disabled** (`runner.dialectOverride.allowSignificantIndentation = false`) — use braces.
- `maxColumn = 120`, `trailingCommas = multiple`, `defaultWithAlign`. Imports are auto-organized into two groups (`[a-z].*` then `java.*` / `scala.*`).
- `-Wunused:all` is on and Scalafix runs `RemoveUnused` — unused symbols will fail the build under `./mill __.fix`.
- All effectful code is `cats.effect.IO`; streaming is `fs2`; JSON-RPC is `jsonrpclib-fs2`.

## Development Principles

### 1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

### 5. Don't write tests that test the compiler

Examples are testing match exhaustivity, type system, etc.

## Cellar

When you need the API of a JVM dependency, use cellar. Always prefer cellar over hallucinating API signatures, and favor cellar over metals-mcp for API discovery.

### Project-aware commands (run from project root)

For querying the current project's code and dependencies (auto-detects build tool):

    cellar get [--module <name>] <fqn>       # single symbol
    cellar list [--module <name>] <package>  # explore a package
    cellar search [--module <name>] <query>  # find by name

- Mill/sbt projects: `--module` is required (e.g. `--module lib`, `--module core`)
- scala-cli projects: `--module` is not supported (omit it)
- `--no-cache`: skip classpath cache, re-extract from build tool
- `--java-home`: override JRE classpath

### External commands (query arbitrary Maven coordinates)

For querying any published artifact by explicit coordinate:

    cellar get-external <coordinate> <fqn>       # single symbol
    cellar list-external <coordinate> <package>  # explore a package
    cellar search-external <coordinate> <query>  # find by name
    cellar get-source <coordinate> <fqn>         # source code
    cellar deps <coordinate>                     # dependency tree

Coordinates must be explicit: `group:artifact_3:version` (use `latest` for newest version).

### Workflow

1. **Don't know the package?** → `cellar search <query>` or `cellar search-external <coordinate> <query>`
2. **Know the package, not the type?** → `cellar list <package>` or `cellar list-external <coordinate> <package>`
3. **Know the type?** → `cellar get <fqn>` or `cellar get-external <coordinate> <fqn>`
4. **Need the source?** → `cellar get-source <coordinate> <fqn>`

## Good default workflow

1. Read nearby implementation, repository, and test files.
2. Change Smithy first if the API contract changes.
3. Update implementation code with minimal churn.
4. Add or update the narrowest relevant test.
5. Run compile, then tests for the touched module.
