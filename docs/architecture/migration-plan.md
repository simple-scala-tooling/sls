# Migration Plan: From Mutex to Layered Operation Processing

## Current State Analysis

### Existing Architecture

The current implementation uses a single `Mutex[IO]` in `StateManager` to serialize all operations:

```scala
class StateManager(
    lspClient: SlsLanguageClient[IO],
    textDocumentSyncManager: TextDocumentSyncManager,
    bspStateManager: BspStateManager,
    mutex: Mutex[IO], // <-- Single bottleneck
) {
  def didOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit] =
    mutex.lock.surround { /* ... */ }
    
  def getBuildTargetInformation(uri: URI): IO[ScalaBuildTargetInformation] =
    mutex.lock.surround { /* ... */ }
}
```

### Problems Addressed
- ✅ Race condition between `didOpen` and subsequent requests
- ✅ Ensures `importBuild` completes before other operations
- ✅ Maintains FIFO ordering for all operations

### Problems Not Addressed
- ❌ Over-serialization of independent operations
- ❌ Performance bottleneck for concurrent requests
- ❌ No support for background processing
- ❌ Poor scalability with increased load

## Migration Strategy

### Approach: Incremental Refactoring

We'll migrate in **three phases** to minimize risk and maintain system stability:

1. **Phase 1**: Replace mutex with critical operations queue (addresses current issues)
2. **Phase 2**: Add concurrent processing for user requests (improves performance)
3. **Phase 3**: Add background task processing (enables future features)

Each phase maintains backward compatibility and can be deployed independently.

## Phase 1: Critical Operations Queue

**Goal**: Replace mutex with explicit queue for critical operations while maintaining current behavior.

### Duration: 1-2 weeks

### Changes Required

#### 1. Create Operation Types

**New file**: `sls/src/org/scala/abusers/sls/operations/Operations.scala`

```scala
package org.scala.abusers.sls.operations

import cats.effect.IO
import cats.effect.kernel.Deferred
import java.net.URI

sealed trait StateOperation[A] {
  def execute(context: OperationContext): IO[A]
}

case class DidOpenOperation(params: lsp.DidOpenTextDocumentParams) extends StateOperation[Unit]
case class DidChangeOperation(params: lsp.DidChangeTextDocumentParams) extends StateOperation[Unit]
case class DidCloseOperation(params: lsp.DidCloseTextDocumentParams) extends StateOperation[Unit]
case class DidSaveOperation(params: lsp.DidSaveTextDocumentParams) extends StateOperation[Unit]
case class GetBuildTargetOperation(uri: URI) extends StateOperation[ScalaBuildTargetInformation]
case class GetDocumentStateOperation(uri: URI) extends StateOperation[DocumentState]
case class ImportBuildOperation() extends StateOperation[Unit]

case class OperationContext(
  lspClient: SlsLanguageClient[IO],
  textDocumentSyncManager: TextDocumentSyncManager,
  bspStateManager: BspStateManager
)
```

#### 2. Create Critical Operations Processor

**New file**: `sls/src/org/scala/abusers/sls/operations/CriticalProcessor.scala`

```scala
package org.scala.abusers.sls.operations

import cats.effect.{IO, Deferred}
import cats.effect.std.Queue
import fs2.Stream
import java.util.UUID

class CriticalProcessor(
  queue: Queue[IO, OperationWrapper[Any]],
  context: OperationContext
) {
  
  case class OperationWrapper[A](
    id: UUID,
    operation: StateOperation[A],
    result: Deferred[IO, Either[Throwable, A]]
  )
  
  def processor: Stream[IO, Nothing] = 
    Stream
      .fromQueueUnterminated(queue)
      .evalMap { wrapper =>
        wrapper.operation.execute(context)
          .attempt
          .flatMap(result => wrapper.result.complete(result))
      }
      .handleErrorWith(err => 
        Stream.eval(context.lspClient.logMessage(s"Critical operation failed: ${err.getMessage}"))
      )
      .repeat
      
  def submit[A](operation: StateOperation[A]): IO[A] = {
    for {
      id <- IO(UUID.randomUUID())
      deferred <- Deferred[IO, Either[Throwable, A]]
      wrapper = OperationWrapper(id, operation, deferred)
      _ <- queue.offer(wrapper)
      result <- deferred.get.flatMap(IO.fromEither)
    } yield result
  }
}

object CriticalProcessor {
  def create(context: OperationContext): IO[CriticalProcessor] = {
    Queue.unbounded[IO, OperationWrapper[Any]]
      .map(queue => new CriticalProcessor(queue, context))
  }
}
```

#### 3. Update StateManager

**Modify**: `sls/src/org/scala/abusers/sls/StateManager.scala`

```scala
class StateManager(
    lspClient: SlsLanguageClient[IO],
    textDocumentSyncManager: TextDocumentSyncManager,
    bspStateManager: BspStateManager,
    criticalProcessor: CriticalProcessor // Replace mutex
) {
  def didOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit] =
    criticalProcessor.submit(DidOpenOperation(params))

  def didChange(params: lsp.DidChangeTextDocumentParams): IO[Unit] =
    criticalProcessor.submit(DidChangeOperation(params))

  def didClose(params: lsp.DidCloseTextDocumentParams): IO[Unit] =
    criticalProcessor.submit(DidCloseOperation(params))

  def didSave(params: lsp.DidSaveTextDocumentParams): IO[Unit] =
    criticalProcessor.submit(DidSaveOperation(params))

  def getDocumentState(uri: URI): IO[DocumentState] =
    criticalProcessor.submit(GetDocumentStateOperation(uri))

  def getBuildTargetInformation(uri: URI): IO[ScalaBuildTargetInformation] =
    criticalProcessor.submit(GetBuildTargetOperation(uri))

  def importBuild: IO[Unit] =
    criticalProcessor.submit(ImportBuildOperation())
}

object StateManager {
  def instance(
    lspClient: SlsLanguageClient[IO], 
    textDocumentSyncManager: TextDocumentSyncManager, 
    bspStateManager: BspStateManager
  ): IO[StateManager] = {
    val context = OperationContext(lspClient, textDocumentSyncManager, bspStateManager)
    CriticalProcessor.create(context)
      .map(processor => new StateManager(lspClient, textDocumentSyncManager, bspStateManager, processor))
  }
}
```

#### 4. Start Processor in ServerImpl

**Modify**: `sls/src/org/scala/abusers/sls/ServerImpl.scala`

```scala
class ServerImpl(/* ... */) {
  
  def initializeOp(params: lsp.InitializeParams): IO[lsp.InitializeOpOutput] = {
    // ... existing initialization code ...
    for {
      // ... existing setup ...
      stateManager <- StateManager.instance(lspClient, textDocumentSyncManager, bspStateManager)
      _ <- stateManager.startProcessor // Start the processor
      // ... rest of initialization ...
    } yield lsp.InitializeOpOutput(/* ... */)
  }
}
```

### Testing Strategy

1. **Unit Tests**: Test each operation type individually
2. **Integration Tests**: Verify FIFO ordering is maintained
3. **Race Condition Tests**: Ensure didOpen/inlayHints race is resolved
4. **Performance Tests**: Confirm no performance regression

### Success Criteria

- ✅ All existing functionality works unchanged
- ✅ Race condition between didOpen and requests is resolved
- ✅ FIFO ordering is maintained
- ✅ No performance regression
- ✅ Error handling is equivalent or better

### Rollback Plan

- Keep mutex-based implementation in a feature flag
- Easy to switch back if issues are discovered
- Gradual rollout to subset of users first

## Phase 2: Concurrent Interactive Requests

**Goal**: Allow user-facing requests to run concurrently while maintaining critical operation ordering.

### Duration: 2-3 weeks

### Changes Required

#### 1. Create Interactive Request Types

**Extend**: `Operations.scala`

```scala
sealed trait UserRequest[A] {
  def execute(context: OperationContext): IO[A]
}

case class CompletionRequest(params: lsp.CompletionParams) extends UserRequest[lsp4j.CompletionList]
case class HoverRequest(params: lsp.HoverParams) extends UserRequest[Option[lsp4j.Hover]]
case class InlayHintsRequest(params: lsp.InlayHintParams) extends UserRequest[List[lsp.InlayHint]]
case class DefinitionRequest(params: lsp.DefinitionParams) extends UserRequest[List[lsp.Location]]
case class SignatureHelpRequest(params: lsp.SignatureHelpParams) extends UserRequest[lsp4j.SignatureHelp]
```

#### 2. Create Interactive Processor

**New file**: `InteractiveProcessor.scala`

```scala
class InteractiveProcessor(
  queue: Queue[IO, OperationWrapper[Any]],
  context: OperationContext,
  maxConcurrency: Int = 10
) {
  def processor: Stream[IO, Nothing] =
    Stream
      .fromQueueUnterminated(queue)
      .parEvalMapUnordered(maxConcurrency) { wrapper =>
        wrapper.operation.execute(context)
          .attempt
          .flatMap(result => wrapper.result.complete(result))
      }
      .handleErrorWith(err => 
        Stream.eval(context.lspClient.logMessage(s"Interactive request failed: ${err.getMessage}"))
      )
      .repeat
}
```

#### 3. Create Layered Operation Manager

**New file**: `LayeredOperationManager.scala`

```scala
class LayeredOperationManager(
  critical: CriticalProcessor,
  interactive: InteractiveProcessor
) {
  def start: IO[Fiber[IO, Throwable, Unit]] = {
    val combinedStream = 
      critical.processor.merge(interactive.processor)
    
    combinedStream.compile.drain.start
  }
  
  def submitCritical[A](op: StateOperation[A]): IO[A] = 
    critical.submit(op)
    
  def submitInteractive[A](op: UserRequest[A]): IO[A] = 
    interactive.submit(op)
}
```

#### 4. Update ServerImpl Request Handlers

```scala
class ServerImpl(/* ... */) {
  
  def handleInlayHints(params: lsp.InlayHintParams): IO[lsp.TextDocumentInlayHintOpOutput] = {
    operationManager.submitInteractive(InlayHintsRequest(params))
      .map(result => lsp.TextDocumentInlayHintOpOutput(result.some))
  }
  
  def handleCompletion(params: lsp.CompletionParams): IO[lsp.TextDocumentCompletionOpOutput] = {
    operationManager.submitInteractive(CompletionRequest(params))
      .map(result => lsp.TextDocumentCompletionOpOutput(result.some))
  }
}
```

### Testing Strategy

1. **Concurrency Tests**: Verify multiple requests can run simultaneously
2. **Ordering Tests**: Ensure critical operations still run in order
3. **Load Tests**: Verify performance improvement under concurrent load
4. **Race Condition Tests**: Ensure new concurrency doesn't introduce races

### Success Criteria

- ✅ Interactive requests run concurrently
- ✅ Critical operations remain sequential
- ✅ Improved response times for user requests
- ✅ No new race conditions introduced

## Phase 3: Background Task Processing

**Goal**: Add support for long-running background tasks that can be interrupted for higher priority work.

### Duration: 2-3 weeks

### Changes Required

#### 1. Create Background Task Types

```scala
sealed trait BackgroundTask[A] {
  def execute(context: OperationContext): IO[A]
  def canBeCancelled: Boolean = true
}

case class IndexingTask(uri: URI) extends BackgroundTask[Unit]
case class CacheWarmupTask(buildTarget: ScalaBuildTargetInformation) extends BackgroundTask[Unit]
case class DiagnosticBatchTask(uris: List[URI]) extends BackgroundTask[List[lsp.Diagnostic]]
```

#### 2. Create Background Processor

```scala
class BackgroundProcessor(
  queue: Queue[IO, OperationWrapper[Any]],
  context: OperationContext,
  pauseSignal: SignallingRef[IO, Boolean]
) {
  def processor: Stream[IO, Nothing] =
    Stream
      .fromQueueUnterminated(queue)
      .pauseWhen(pauseSignal)
      .evalMap { wrapper =>
        wrapper.operation.execute(context)
          .attempt
          .flatMap(result => wrapper.result.complete(result))
      }
      .handleErrorWith(err => 
        Stream.eval(context.lspClient.logMessage(s"Background task failed: ${err.getMessage}"))
      )
      .repeat
      
  def pauseBackground: IO[Unit] = pauseSignal.set(true)
  def resumeBackground: IO[Unit] = pauseSignal.set(false)
}
```

#### 3. Update Layered Operation Manager

```scala
class LayeredOperationManager(
  critical: CriticalProcessor,
  interactive: InteractiveProcessor,
  background: BackgroundProcessor
) {
  def submitBackground[A](op: BackgroundTask[A]): IO[Fiber[IO, Throwable, A]] = {
    // Return fiber for cancellation support
    background.submit(op).start
  }
  
  // Pause background tasks when user requests are active
  def onUserRequestStart: IO[Unit] = background.pauseBackground
  def onUserRequestEnd: IO[Unit] = background.resumeBackground
}
```

### Testing Strategy

1. **Interruption Tests**: Verify background tasks can be paused/resumed
2. **Priority Tests**: Ensure user requests take priority over background tasks
3. **Cancellation Tests**: Verify background tasks can be cancelled
4. **Resource Tests**: Ensure background tasks don't consume excessive resources

### Success Criteria

- ✅ Background tasks run when system is idle
- ✅ Background tasks pause for user requests
- ✅ Background tasks can be cancelled
- ✅ No impact on user request performance

## Risk Mitigation

### Technical Risks

1. **Concurrency Bugs**: Extensive testing and gradual rollout
2. **Performance Regression**: Benchmark each phase
3. **Memory Leaks**: Monitor queue sizes and background task cleanup
4. **Deadlocks**: Careful design of inter-layer communication

### Operational Risks

1. **Breaking Changes**: Maintain API compatibility during migration
2. **Deployment Issues**: Feature flags for easy rollback
3. **User Impact**: Gradual rollout with monitoring

### Mitigation Strategies

1. **Feature Flags**: Each phase behind a configurable flag
2. **Monitoring**: Add metrics for queue sizes, processing times, error rates
3. **Rollback Plan**: Keep old implementation available
4. **Testing**: Comprehensive test suite for each phase

## Success Metrics

### Performance Metrics

- **Response Time**: Interactive requests should be 2-3x faster
- **Throughput**: System should handle 5-10x more concurrent requests
- **Resource Usage**: Memory usage should remain stable

### Reliability Metrics

- **Error Rate**: Should not increase from current baseline
- **Race Conditions**: Zero race condition reports
- **System Stability**: No crashes or hangs

### User Experience Metrics

- **Editor Responsiveness**: Improved perceived performance
- **Feature Availability**: Background indexing enables new features
- **System Resources**: Lower CPU usage during idle periods

## Timeline Summary

| Phase | Duration | Key Deliverables | Risk Level |
|-------|----------|------------------|------------|
| **Phase 1** | 1-2 weeks | Critical operations queue, race condition fix | Low |
| **Phase 2** | 2-3 weeks | Concurrent user requests, performance improvement | Medium |
| **Phase 3** | 2-3 weeks | Background processing, future feature foundation | Medium |
| **Total** | **5-8 weeks** | Complete layered operation processing | |

## Conclusion

This migration plan provides a safe, incremental path from the current mutex-based approach to a modern, scalable layered operation processing architecture. By splitting the migration into three phases, we minimize risk while steadily improving system performance and capabilities.

Each phase delivers immediate value while building toward the final architecture that addresses both current issues and future requirements.