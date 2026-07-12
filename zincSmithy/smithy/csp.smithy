$version: "2.0"

// CSP - Compile Server Protocol
namespace org.scala.abusers.csp

use jsonrpclib#jsonRpc
use jsonrpclib#jsonRpcRequest
use jsonrpclib#jsonRpcNotification
use jsonrpclib#jsonRpcPayload

@jsonRpc
service CspClient {
  operations: [
    onPublishDiagnostics
  ]
}

@jsonRpc
service CspServer {
  operations: [
    Compile
  ]
}

@jsonRpcRequest("compile")
operation Compile {
  input: CompileParams
  output: CompileOutput
}

@jsonRpcNotification("logMessage")
operation onLogMessage {
    input := {
        @jsonRpcPayload
        @required
        params: String
    }
    output: Unit
}

@jsonRpcNotification("publishDiagnostics")
operation onPublishDiagnostics {
    input := {
        @jsonRpcPayload
        @required
        params: PublishDiagnosticsParams
    }
    output: Unit
}

/// Per-file compile diagnostics. An empty `diagnostics` list clears the file: the server tracks which
/// files it reported problems for (per scopeId) and re-publishes them empty once they compile clean.
structure PublishDiagnosticsParams {
  /// Absolute path of the source file the diagnostics belong to.
  @required
  file: String
  @required
  scopeId: String
  @required
  diagnostics: CompileDiagnostics
}

list CompileDiagnostics {
  member: CompileDiagnostic
}

structure CompileDiagnostic {
  @required
  message: String
  @required
  severity: DiagnosticSeverity
  /// Absent when the compiler reported no position (e.g. a global error).
  range: DiagnosticRange
}

/// 0-based lines and characters, LSP convention.
structure DiagnosticRange {
  @required
  startLine: Integer
  @required
  startCharacter: Integer
  @required
  endLine: Integer
  @required
  endCharacter: Integer
}

enum DiagnosticSeverity {
  ERROR
  WARNING
  INFO
}

@jsonRpcNotification("taskProgress")
operation onTaskProgress {
    input := {
        @jsonRpcPayload
        @required
        params: String
    }
    output: Unit
}

structure CompileParams {
  @required
  scopeId: String
  @required
  classpath: Classpath
  @required
  sourcePath: SourcePath
  @required
  scalacOptions: ScalacOptions
  @required
  javacOptions: JavacOptions
  @required
  scalaVersion: ScalaVersion
}

list Classpath {
  member: String
}

list SourcePath {
  member: String
}

list ScalacOptions {
  member: String
}

list JavacOptions {
  member: String
}

structure CompileOutput {
  @required
  outputJar: String
  @required
  changedFiles: ChangedFilesMap
  @required
  outputFormat: OutputFormat
  /// False when compilation failed. `outputJar` and `changedFiles` are then meaningless and
  /// must not be acted on; diagnostics arrive via `publishDiagnostics` notifications.
  @required
  success: Boolean
}

map ChangedFilesMap {
  key: String
  value: ProductFiles
}

list ProductFiles {
  member: String
}

enum OutputFormat {
  TASTY
  BETASTY
}

structure ScalaVersion {
  @required
  version: String
}
