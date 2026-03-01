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
        params: String
    }
    output: Unit
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
}

structure ScalaVersion {
  @required
  version: String
}
