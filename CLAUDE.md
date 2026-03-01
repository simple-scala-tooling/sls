# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System

This project uses **Mill** (v1.1.1). The bootstrap script `./mill` is included in the repo.

```bash
./mill compile              # Compile all modules
./mill sls.compile          # Compile specific module
./mill _.test               # Run all tests (this is what CI runs)
./mill sls.test             # Run tests for a specific module
./mill sls.assembly         # Build fat JAR (output: out/sls/assembly.dest/out.jar)
```

Build definition is in `build.mill`. Scala 3.7.4. JDK 17+ required (JDK 21 recommended).

## Architecture

**SLS (Simple Language Server)** is an LSP server for Scala, built with cats-effect IO and Smithy4s.

### Module Structure

- **`sls`** — Main LSP server. Entry point: `SimpleScalaServer` in `SimpleLanguageServer.scala`. Request handlers in `ServerImpl.scala`. Depends on all other modules.
- **`slsSmithy`** — LSP protocol definitions in Smithy (`.smithy` files). Smithy4s generates Scala traits and JSON-RPC bindings.
- **`zincCli`** — Standalone incremental compiler wrapping sbt-zinc, communicates via JSON-RPC (CSP protocol). Runs as a subprocess.
- **`zincSmithy`** — Compile Server Protocol (CSP) definitions in Smithy.
- **`profilingRuntime`** — OpenTelemetry + Pyroscope profiling. Provides `ProfilingIOApp` base class.
- **`tastyPresentationCompiler`** — TASTy-based presentation compiler integration.

### Request Flow

```
LSP Client → (JSON-RPC/stdio) → SimpleLanguageServer → ServerImpl
  ServerImpl uses:
    ├─ PresentationCompilerProvider (completions, hover, definitions, etc.)
    ├─ BspStateManager (talks to Mill via BSP for build info)
    ├─ CspClient (talks to zinc-cli subprocess for incremental compilation)
    ├─ TextDocumentSyncManager (open file buffers)
    └─ DiagnosticManager (error/warning publishing)
```

### Key Patterns

- **Smithy4s code generation**: Protocol definitions live in `.smithy` files under `slsSmithy/smithy/` and `zincSmithy/smithy/`. Changes to protocols start there.
- **Cats-effect IO everywhere**: All async operations use `IO`, resources use `Resource[IO, A]`.
- **Two-process architecture**: `sls` (LSP frontend) spawns `zinc-cli` (compiler backend) as a subprocess, communicating via JSON-RPC.

### Testing

Tests use **Weaver-Cats** (`SimpleIOSuite`). Test sources are under `<module>/test/src/`.

## Development Principles

1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:

    State your assumptions explicitly. If uncertain, ask.
    If multiple interpretations exist, present them - don't pick silently.
    If a simpler approach exists, say so. Push back when warranted.
    If something is unclear, stop. Name what's confusing. Ask.

2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

    No features beyond what was asked.
    No abstractions for single-use code.
    No "flexibility" or "configurability" that wasn't requested.
    No error handling for impossible scenarios.
    If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

3. Surgical Changes

Touch only what you must. Clean up only your own mess.

When editing existing code:

    Don't "improve" adjacent code, comments, or formatting.
    Don't refactor things that aren't broken.
    Match existing style, even if you'd do it differently.
    If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

    Remove imports/variables/functions that YOUR changes made unused.
    Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

4. Goal-Driven Execution

Define success criteria. Loop until verified.

Transform tasks into verifiable goals:

    "Add validation" → "Write tests for invalid inputs, then make them pass"
    "Fix the bug" → "Write a test that reproduces it, then make it pass"
    "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

5. Don't write tests that test the compiler

Examples are testing match exhaustivity, typesystem etc.

6. Library API discovery should always use cellar.

cellar get <coordinate> <fqn> # single symbol
cellar get-source <coordinate> <fqn> # source code
cellar list <coordinate> <package> # explore a package
cellar search <coordinate> <query> # find by name
cellar deps <coordinate> # dependency tree

Coordinates must be explicit: group:artifact_3:version

Always favor usage of cellar before metals-mcp
