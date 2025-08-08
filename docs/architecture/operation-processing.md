# Operation Processing Architecture

## Problem Statement

The current LSP server implementation has a race condition between `didOpen` notifications and subsequent requests like `inlayHints`. The issue occurs because:

1. **didOpen is a notification** (fire-and-forget from client perspective)
2. **inlayHints is a request** that can arrive while didOpen is still processing
3. **Race condition**: If `bspStateManager.get()` is called before `bspStateManager.didOpen()` completes, it throws an exception

### Current Solution Issues

The current `Mutex` approach in `StateManager` has several problems:
- **Over-synchronization**: All operations are serialized, including read-only ones
- **Performance bottleneck**: Completion, hover, inlay hints all wait for each other unnecessarily  
- **Deadlock potential**: If any operation hangs, it blocks everything
- **Scalability concerns**: Doesn't address future needs for background processing

## Requirements

### Current Requirements
1. **Ordered Processing**: All LSP requests must be processed in the order they arrive (FIFO)
2. **Import Build Dependency**: Nothing should process until `importBuild` completes
3. **didOpen Dependency**: Requests need build target info, which comes from didOpen

### Future Requirements  
4. **Background Tasks**: Long-running indexing that can be interrupted/deprioritized
5. **Priority Handling**: High-priority user requests that can jump the queue
6. **Concurrent Processing**: Some operations can run concurrently without conflicts

## Proposed Architecture: Layered Operation Processing

### Overview

A three-layer architecture that separates operations by their synchronization and priority requirements:

```
┌─────────────────────────────────────────────────────────────┐
│                    Layer 3: Background Tasks                │
│                   (Interruptible, Low Priority)             │
├─────────────────────────────────────────────────────────────┤
│                   Layer 2: Interactive Requests             │
│                  (Concurrent, User Priority)                │
├─────────────────────────────────────────────────────────────┤
│                   Layer 1: Critical Operations              │
│                    (Sequential, Highest Priority)           │
└─────────────────────────────────────────────────────────────┘
```

### Layer 1: Critical State Operations

**Purpose**: Handle operations that must be strictly ordered and complete before other operations can proceed.

**Operations**:
- Document lifecycle: `didOpen`, `didClose`, `didSave`
- Build target resolution
- Import build
- State mutations that affect other operations

**Processing**: Strict FIFO ordering using a single sequential processor.

### Layer 2: Interactive User Requests

**Purpose**: Handle user-facing requests that need fast response times but can run concurrently.

**Operations**:
- Completions, hover, inlayHints, definitions
- Signature help, document symbols
- Any request that reads state but doesn't mutate it

**Processing**: Concurrent processing with coordination to avoid conflicts.

### Layer 3: Background Tasks

**Purpose**: Handle long-running, non-urgent operations that enhance user experience.

**Operations**:
- Indexing, caching, precomputation
- Dependency analysis
- Batch diagnostics

**Processing**: Interruptible and pausable, lowest priority.

## Implementation with fs2

### Core Types

```scala
sealed trait OperationPriority
case object Critical extends OperationPriority      // Layer 1
case object Interactive extends OperationPriority   // Layer 2  
case object Background extends OperationPriority    // Layer 3

sealed trait Operation[A] {
  def priority: OperationPriority
  def execute(context: ExecutionContext): IO[A]
}

case class CriticalOp[A](op: StateOperation[A]) extends Operation[A] {
  def priority = Critical
}

case class InteractiveOp[A](op: UserRequest[A]) extends Operation[A] {
  def priority = Interactive  
}

case class BackgroundOp[A](op: BackgroundTask[A]) extends Operation[A] {
  def priority = Background
}
```

### Layer 1: Critical Operations Processor

```scala
class CriticalProcessor(queue: Queue[IO, CriticalOp[Any]]) {
  def processor: Stream[IO, Nothing] = 
    Stream
      .fromQueueUnterminated(queue)
      .evalMap(_.execute(context))
      .handleErrorWith(err => Stream.eval(logError(err)))
      .repeat
      
  def submit[A](op: StateOperation[A]): IO[A] = {
    for {
      deferred <- Deferred[IO, Either[Throwable, A]]
      wrappedOp = CriticalOp(op.withResultHandler(deferred))
      _ <- queue.offer(wrappedOp)
      result <- deferred.get.flatMap(IO.fromEither)
    } yield result
  }
}
```

### Layer 2: Interactive Requests Processor

```scala
class InteractiveProcessor(
  queue: Queue[IO, InteractiveOp[Any]],
  maxConcurrency: Int = 10
) {
  def processor: Stream[IO, Nothing] =
    Stream
      .fromQueueUnterminated(queue)
      .parEvalMapUnordered(maxConcurrency)(_.execute(context))
      .handleErrorWith(err => Stream.eval(logError(err)))
      .repeat
      
  def submit[A](op: UserRequest[A]): IO[A] = {
    // Similar to CriticalProcessor but allows concurrent execution
    for {
      deferred <- Deferred[IO, Either[Throwable, A]]
      wrappedOp = InteractiveOp(op.withResultHandler(deferred))
      _ <- queue.offer(wrappedOp)
      result <- deferred.get.flatMap(IO.fromEither)
    } yield result
  }
}
```

### Layer 3: Background Tasks Processor

```scala
class BackgroundProcessor(
  queue: Queue[IO, BackgroundOp[Any]],
  pauseSignal: SignallingRef[IO, Boolean]
) {
  def processor: Stream[IO, Nothing] =
    Stream
      .fromQueueUnterminated(queue)
      .pauseWhen(pauseSignal)  // fs2 built-in pause mechanism
      .evalMap(_.execute(context))
      .handleErrorWith(err => Stream.eval(logError(err)))
      .repeat
      
  def submit[A](op: BackgroundTask[A]): IO[Fiber[IO, Throwable, A]] = {
    // Background tasks return Fiber for cancellation
    for {
      deferred <- Deferred[IO, Either[Throwable, A]]
      wrappedOp = BackgroundOp(op.withResultHandler(deferred))
      _ <- queue.offer(wrappedOp)
      fiber <- deferred.get.flatMap(IO.fromEither).start
    } yield fiber
  }
  
  def pauseBackground: IO[Unit] = pauseSignal.set(true)
  def resumeBackground: IO[Unit] = pauseSignal.set(false)
}
```

### Orchestration Layer

```scala
class LayeredOperationManager(
  critical: CriticalProcessor,
  interactive: InteractiveProcessor, 
  background: BackgroundProcessor
) {
  def start: IO[Fiber[IO, Throwable, Unit]] = {
    val combinedStream = 
      critical.processor
        .merge(interactive.processor)
        .merge(background.processor)
    
    combinedStream.compile.drain.start
  }
  
  // Public API methods
  def submitCritical[A](op: StateOperation[A]): IO[A] = 
    critical.submit(op)
    
  def submitInteractive[A](op: UserRequest[A]): IO[A] = 
    interactive.submit(op)
    
  def submitBackground[A](op: BackgroundTask[A]): IO[Fiber[IO, Throwable, A]] = 
    background.submit(op)
}
```

## Integration with Current Codebase

### StateManager Integration

```scala
class StateManager(
  operationManager: LayeredOperationManager,
  // Remove mutex: Mutex[IO] - no longer needed
  lspClient: SlsLanguageClient[IO],
  textDocumentSyncManager: TextDocumentSyncManager,
  bspStateManager: BspStateManager
) {
  def didOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit] =
    operationManager.submitCritical(DidOpenOperation(params))

  def didChange(params: lsp.DidChangeTextDocumentParams): IO[Unit] =
    operationManager.submitCritical(DidChangeOperation(params))

  def getBuildTargetInformation(uri: URI): IO[ScalaBuildTargetInformation] =
    operationManager.submitInteractive(GetBuildTargetOperation(uri))

  def importBuild: IO[Unit] =
    operationManager.submitCritical(ImportBuildOperation())
}
```

### ServerImpl Integration

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

## Benefits

### Immediate Benefits
1. **Solves Race Condition**: Critical operations are properly ordered
2. **Better Performance**: Interactive requests can run concurrently
3. **Explicit Priorities**: Clear separation of operation types
4. **Error Isolation**: Failed operations don't block entire system

### Future Benefits
1. **Background Processing**: Foundation for indexing and caching
2. **Scalability**: Can handle increased load through concurrency
3. **Observability**: Easy to add metrics, logging, and monitoring
4. **Testability**: Each layer can be tested independently

### fs2 Advantages
1. **Backpressure**: Natural flow control prevents memory issues
2. **Resource Safety**: Automatic cleanup and error handling
3. **Composability**: Easy to combine and extend streams
4. **Performance**: Efficient concurrent processing
5. **Cancellation**: Built-in support for interrupting operations

## Trade-offs

### Complexity
- **Increased Complexity**: More moving parts than simple mutex
- **Learning Curve**: Team needs to understand fs2 streams
- **Debugging**: Stream-based processing can be harder to debug

### Resource Usage
- **Memory Overhead**: Multiple queues and processors
- **Thread Usage**: More concurrent operations

### Migration Risk
- **Breaking Changes**: Significant refactoring required
- **Testing Effort**: Need comprehensive testing of concurrent scenarios

## Conclusion

The layered operation processing architecture provides a robust solution to the current race condition while building a foundation for future performance and feature requirements. The fs2-based implementation leverages battle-tested streaming primitives to ensure correctness and performance.

The architecture addresses immediate needs (ordered processing, import build dependency) while enabling future capabilities (background processing, priority handling, concurrent execution).