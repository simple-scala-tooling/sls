# Integration Testing Plan for Scala Language Server (SLS)

## Overview
Create comprehensive integration tests leveraging the Smithy-generated LSP and BSP APIs to validate the full protocol flow and real-world usage scenarios for the Simple Language Server.

## Project Architecture Context

The SLS project uses a sophisticated architecture built around:
- **Smithy Protocol Definitions**: LSP and BSP protocols defined in `slsSmithy/smithy/lsp.smithy`
- **Generated Type-Safe APIs**: Smithy4s generates `SlsLanguageServer[IO]` and `SlsLanguageClient[IO]` interfaces
- **BSP Integration**: Connects to Bloop build server for compilation and build management
- **Presentation Compiler**: Uses Scala 3.7.2+ presentation compiler for language features
- **Cats Effect IO**: Functional effect system with proper resource management

## Current Test State

### Existing Tests
- **Unit Tests**: `ResourceSupervisorSpec` - Resource management testing
- **Document Sync Tests**: `TextDocumentSyncSuite` - Basic text synchronization testing
- **Test Framework**: Weaver with cats-effect support

### Gap Analysis
- ❌ **No LSP protocol integration tests** - Server lifecycle, client-server communication
- ❌ **No BSP integration tests** - Build server connection, compilation workflow  
- ❌ **No real-world scenario tests** - Multi-file projects, performance, concurrency
- ❌ **No error handling tests** - Protocol errors, timeouts, cancellation

## Integration Testing Strategy

### 1. Leverage Smithy-Generated Structure

#### Benefits of Smithy-Based Testing
- **Type Safety**: Generated case classes ensure compile-time protocol validation
- **Protocol Completeness**: All LSP operations defined in Smithy schema
- **Serialization**: Built-in JSON-RPC serialization/deserialization
- **Client/Server Interfaces**: `SlsLanguageServer[IO]` and `SlsLanguageClient[IO]` provide clean APIs

#### Generated Types Available
```scala
// LSP Protocol
trait SlsLanguageServer[F[_]] {
  def initializeOp(params: InitializeParams): F[InitializeOpOutput]
  def textDocumentCompletionOp(params: CompletionParams): F[CompletionOpOutput]
  def textDocumentHoverOp(params: HoverParams): F[HoverOpOutput]
  // ... all other LSP operations
}

trait SlsLanguageClient[F[_]] {
  def publishDiagnosticsOp(params: PublishDiagnosticsParams): F[Unit]
  def showMessageOp(params: ShowMessageParams): F[Unit]
  // ... client notifications
}

// BSP Protocol (via bsp4s)
trait BuildServer[F[_]] {
  def buildInitialize(params: InitializeBuildParams): F[InitializeBuildResult]
  def buildTargets(): F[WorkspaceBuildTargetsResult]
  // ... BSP operations
}
```

### 2. Test Infrastructure Design

#### A. LSP Integration Test Framework

**File Structure:**
```
sls/test/src/org/scala/abusers/sls/integration/
├── LSPIntegrationTestSuite.scala      # Base test infrastructure  
├── ProtocolLifecycleTests.scala       # Initialize/shutdown tests
├── TextDocumentSyncTests.scala        # Document sync integration
├── LanguageFeaturesTests.scala        # Completion, hover, definition
├── ErrorHandlingTests.scala           # Protocol error scenarios
└── utils/
    ├── TestLSPClient.scala            # Mock LSP client implementation
    ├── TestWorkspace.scala            # Test project fixtures
    └── TestUtils.scala                # Common test utilities
```

#### B. BSP Integration Test Framework

**File Structure:**
```
sls/test/src/org/scala/abusers/sls/integration/bsp/
├── BSPIntegrationTests.scala          # BSP connection and lifecycle
├── BuildCompilationTests.scala       # Compilation workflow tests  
├── MillIntegrationTests.scala        # Mill build import tests
└── utils/
    ├── MockBSPServer.scala            # Mock BSP server for testing
    └── BSPTestUtils.scala             # BSP-specific test utilities
```

### 3. Test Categories and Implementation

#### A. LSP Protocol Integration Tests

##### 1. Server Lifecycle Tests (`ProtocolLifecycleTests.scala`)
```scala
object ProtocolLifecycleTests extends SimpleIOSuite {
  test("server initializes with correct capabilities") { _ =>
    for {
      testClient <- TestLSPClient.create
      server     <- ServerImpl.create(testClient)
      response   <- server.initializeOp(InitializeParams(...))
    } yield {
      expect(response.capabilities.textDocumentSync.isDefined) &&
      expect(response.capabilities.completionProvider.isDefined) &&
      expect(response.capabilities.hoverProvider.isDefined)
    }
  }
  
  test("server handles shutdown gracefully") { /* ... */ }
}
```

##### 2. Text Document Synchronization (`TextDocumentSyncTests.scala`)
- **Document Opening**: Test `didOpen` with various file types
- **Incremental Changes**: Test `didChange` with partial updates
- **Document Closing**: Test `didClose` and resource cleanup
- **Save Operations**: Test `didSave` and diagnostic updates

##### 3. Language Features (`LanguageFeaturesTests.scala`)
- **Code Completion**: Test completion requests with various contexts
- **Hover Information**: Test hover responses for symbols, types
- **Go to Definition**: Test definition lookup across files
- **Signature Help**: Test signature assistance for methods
- **Inlay Hints**: Test type and parameter hints

#### B. BSP Integration Tests

##### 1. Build Server Connection (`BSPIntegrationTests.scala`)
```scala
object BSPIntegrationTests extends SimpleIOSuite {
  test("connects to Bloop BSP server") { _ =>
    for {
      workspace  <- TestWorkspace.withMillProject
      server     <- ServerImpl.createWithWorkspace(workspace)
      _          <- server.initialize(...)
      buildTargets <- server.bspClient.buildTargets()
    } yield expect(buildTargets.targets.nonEmpty)
  }
}
```

##### 2. Compilation Workflow (`BuildCompilationTests.scala`)
- **Target Discovery**: Test build target identification
- **Compilation Requests**: Test compile operations via BSP
- **Diagnostic Publishing**: Test diagnostic flow from BSP to LSP client
- **Dependency Resolution**: Test classpath and dependency handling

##### 3. Mill Integration (`MillIntegrationTests.scala`)
- **Build Import**: Test Mill build.mill parsing and import
- **Bloop Plugin**: Test Mill Bloop plugin integration
- **Module Dependencies**: Test cross-module dependency resolution

#### C. Real-World Scenario Tests

##### 1. Multi-File Project Tests
- **Cross-File Navigation**: Test go-to-definition across files
- **Project-Wide Completion**: Test completion with project dependencies
- **Module Dependencies**: Test multi-module project handling

##### 2. Performance and Concurrency Tests
- **Large File Handling**: Test performance with large Scala files
- **Concurrent Requests**: Test multiple simultaneous LSP operations
- **Debounced Diagnostics**: Test diagnostic debouncing behavior (300ms)

##### 3. Error Scenarios and Edge Cases
- **Invalid Requests**: Test malformed LSP requests
- **BSP Connection Failures**: Test build server connection issues
- **Timeout Handling**: Test operation cancellation and timeouts
- **File System Changes**: Test workspace file modifications

### 4. Test Fixtures and Utilities

#### A. Test Workspace Management (`TestWorkspace.scala`)
```scala
object TestWorkspace {
  def withMillProject: IO[TestWorkspace] = {
    // Create temporary directory with sample Mill project
    // Include build.mill, source files, dependencies
  }
  
  def withMultiModuleProject: IO[TestWorkspace] = {
    // Create multi-module Mill project for testing
  }
  
  def withLargeProject: IO[TestWorkspace] = {
    // Create project with many files for performance testing
  }
}
```

#### B. Mock LSP Client (`TestLSPClient.scala`)
```scala
class TestLSPClient extends SlsLanguageClient[IO] {
  private val diagnostics = Ref.unsafe[IO, List[PublishDiagnosticsParams]](List.empty)
  
  def publishDiagnosticsOp(params: PublishDiagnosticsParams): IO[Unit] =
    diagnostics.update(_ :+ params)
    
  def getPublishedDiagnostics: IO[List[PublishDiagnosticsParams]] =
    diagnostics.get
}
```

#### C. Test Fixtures (`sls/test/resources/`)
```
test/resources/
├── projects/
│   ├── simple-scala/          # Basic Scala project with Mill
│   │   ├── build.mill
│   │   └── src/Main.scala
│   ├── multi-module/          # Multi-module project
│   │   ├── build.mill
│   │   ├── core/src/
│   │   └── app/src/
│   └── large-project/         # Performance testing project
└── expected-responses/        # Expected LSP response data
    ├── completion/
    ├── hover/
    └── diagnostics/
```

### 5. Implementation Phases

#### Phase 1: Core Infrastructure (Week 1)
1. ✅ Update `docs/plan_1.md` with comprehensive plan
2. 📝 Create `LSPIntegrationTestSuite` base class
3. 📝 Implement `TestLSPClient` and `TestWorkspace` utilities
4. 📝 Add basic test fixtures and sample projects

#### Phase 2: LSP Protocol Tests (Week 2)
1. 📝 Implement server lifecycle tests (`initializeOp`, shutdown)
2. 📝 Implement text document synchronization tests
3. 📝 Add language features integration tests
4. 📝 Implement error handling and edge case tests

#### Phase 3: BSP Integration Tests (Week 3)
1. 📝 Create BSP integration test framework
2. 📝 Implement build server connection tests
3. 📝 Add compilation workflow tests
4. 📝 Implement Mill build import tests

#### Phase 4: Real-World Scenarios (Week 4)
1. 📝 Add multi-file project navigation tests
2. 📝 Implement performance and concurrency tests
3. 📝 Add comprehensive error scenario coverage
4. 📝 Validate and document all test results

### 6. Success Criteria

#### Test Coverage Goals
- **~25-30 integration tests** covering all major LSP operations
- **~10-15 BSP integration tests** for build server functionality
- **~5-10 real-world scenario tests** for complex workflows
- **100% coverage** of Smithy-defined LSP operations

#### Quality Metrics
- **Type Safety**: All tests use generated Smithy types
- **Maintainability**: Tests are well-structured and documented
- **Performance**: Tests complete within reasonable time bounds
- **Reliability**: Tests pass consistently and catch regressions

#### Documentation Deliverables
- **Test Architecture Documentation**: How to write and run integration tests
- **Test Scenario Coverage**: What scenarios are tested and why
- **Performance Benchmarks**: Expected performance characteristics
- **Troubleshooting Guide**: Common test failures and solutions

## Conclusion

This comprehensive integration testing plan leverages the project's Smithy-based architecture to create robust, type-safe tests that validate the complete LSP and BSP protocol flow. By using the generated APIs and focusing on real-world scenarios, these tests will ensure the Simple Language Server works reliably in production environments.

The phased implementation approach allows for incremental progress while maintaining test quality and coverage. The emphasis on Smithy-generated types ensures tests remain maintainable and aligned with the protocol definitions.