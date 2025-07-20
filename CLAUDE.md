# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System and Commands

This project uses Mill as the build tool. Essential commands:

- **Build**: `./mill sls.compile` - Compiles the main module
- **Test**: `./mill sls.test` - Runs all tests  
- **Test single**: `./mill sls.test.testOnly org.scala.abusers.sls.integration.LanguageFeaturesTests` - Run specific test class
- **Assembly**: `./mill sls.assembly` - Creates executable JAR
- **Run**: `./mill sls.run` - Runs the language server

Try to use Metals MCP to compile, test or run tests

### Development Workflow

- When using multiline string always use """ syntax with | aligned with the third quote and finished with """.stripMargin
- Always check available methods / signatures before using it via metals mcp  
- Before running tests via metals mcp, first compile them with `./mill sls.test.compile`
- Try to sketch down tasks in ./docs/plan_$number.md

### Test Categories

- **LanguageFeaturesTests**: Tests LSP language features (completion, hover, definition, etc.) - uses real mill BSP server
- **BSPIntegrationTests**: Tests BSP server integration and capabilities
- **TextDocumentSyncIntegrationTests**: Tests document lifecycle management
- **RealWorldScenarioTests**: Performance and stress tests
- **ProtocolLifecycleTests**: Tests LSP protocol state management

## Architecture Overview

### Core Components

**SimpleScalaServer** (main entry point): Sets up LSP server with JSON-RPC over stdin/stdout. Creates the dependency graph of core services and wires them together.

**ServerImpl**: Main LSP server implementation handling all LSP protocol methods. Delegates to specialized managers for different concerns.

**StateManager**: Coordinates between text document state and BSP build target information. Acts as the bridge between LSP document operations and build server queries.

**BspStateManager**: Manages BSP (Build Server Protocol) connections and tracks mapping between source files and their build targets. Handles build target discovery and caching.

**TextDocumentSyncManager**: Tracks document state (open, modified, saved, closed). Maintains document versions and content for presentation compiler.

**PresentationCompilerProvider**: Manages Scala 3 presentation compiler instances per build target. Provides completions, hover info, diagnostics, and other language features.

**DiagnosticManager**: Handles diagnostic publishing with debouncing to avoid excessive updates during rapid typing.

### Key Data Flow

1. **Initialization**: LSP client connects → ServerImpl.initializeOp → mill BSP server discovery → BspStateManager.importBuild → build targets cached
2. **Document Open**: textDocument/didOpen → StateManager → BspStateManager.didOpen → maps file to build target → creates presentation compiler
3. **Language Features**: completion/hover requests → StateManager gets build target info → PresentationCompilerProvider provides language service
4. **Document Changes**: textDocument/didChange → debounced diagnostics → presentation compiler analysis

### BSP Integration

The server discovers and connects to mill's BSP server automatically during initialization. It uses `findMillExec()` to locate the mill executable and runs `mill.contrib.bloop.Bloop/install` to set up BSP.

Build targets are mapped to source files through BSP's `buildTargetInverseSources` API. Each source file gets associated with a build target containing classpath, compiler options, and Scala version info.

### Smithy Code Generation

The `slsSmithy` module uses Smithy4s to generate LSP and BSP protocol types from `.smithy` definitions. This ensures type-safe protocol handling and keeps the codebase in sync with protocol specifications.

### Testing Infrastructure

Tests use a sophisticated setup with:
- **TestWorkspace**: Creates temporary mill projects with proper build.mill and sources
- **TestLSPClient**: Mock LSP client for capturing server responses  
- **MockBSPServer**: Smithy4s-based BSP service stubs for basic connection testing

Integration tests create real mill workspaces and test against actual mill BSP servers to ensure realistic behavior.

#### BSP Testing Pattern

For BSP testing, use smithy4s patterns rather than complex mocking:
- Create simple stub implementations of `bsp.BuildServer[IO]`, `bsp.scala_.ScalaBuildServer[IO]`, etc.
- These provide predictable test data and type-safe interfaces
- Example: `StubBuildServer`, `StubScalaBuildServer` in `MockBSPServer.scala`
- This approach automatically stays in sync with protocol changes

## Important Implementation Notes

- Uses Scala 3.7.2 nightly build
- Leverages cats-effect for async/concurrent programming  
- fs2 for streaming and resource management
- Chimney for type transformations between protocol types
- Never mock the BSP server for LanguageFeaturesTests - they require real compilation
- Mill execution requires finding the original mill executable (not copying to temp dirs due to permission issues)

