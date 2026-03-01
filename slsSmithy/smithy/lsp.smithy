$version: "2.0"

namespace org.scala.abusers.sls

use jsonrpclib#jsonRpc
use jsonrpclib#jsonRpcPayload
use jsonrpclib#jsonRpcRequest

@jsonRpc
service SlsLanguageClient {
    operations: [
      lsp#Initialized,
      lsp#WindowLogMessage,
      lsp#WindowShowMessage,
      lsp#TextDocumentPublishDiagnostics
    ]
}

@jsonRpc
service SlsLanguageServer {
    operations: [
      lsp#Initialized,
      lsp#InitializeOp,
      lsp#TextDocumentDidOpen,
      lsp#TextDocumentDidClose,
      lsp#TextDocumentDidChange,
      lsp#TextDocumentDidSave,
      lsp#TextDocumentCompletionOp,
      lsp#TextDocumentHoverOp,
      lsp#TextDocumentSignatureHelpOp,
      lsp#TextDocumentDefinitionOp,
      lsp#TextDocumentInlayHintOp,
      lsp#TextDocumentReferencesOp,
      lsp#WorkspaceSymbolOp,
      lsp#TextDocumentRenameOp,
      lsp#TextDocumentPrepareRenameOp,
      lsp#TextDocumentImplementationOp,
      lsp#TextDocumentPrepareTypeHierarchyOp,
      lsp#TypeHierarchySupertypesOp,
      lsp#TypeHierarchySubtypesOp,
      lsp#WorkspaceDidDeleteFiles,
      SlsDebugIndexOp,
    ]
}

@jsonRpcRequest("sls/debugIndex")
operation SlsDebugIndexOp {
    input := {
        @jsonRpcPayload
        params: SlsDebugIndexParams
    }
    output := {
        @jsonRpcPayload
        result: SlsDebugIndexResult
    }
}

structure SlsDebugIndexParams {
    /// Optional query to search symbols. If empty, returns only stats.
    query: String
}

structure SlsDebugIndexResult {
    projectSymbolCount: Integer
    dependencySymbolCount: Integer
    projectFileCount: Integer
    indexedJarCount: Integer
    /// Symbols matching the query (empty if no query provided)
    matchingSymbols: DebugSymbolList
}

list DebugSymbolList {
    member: DebugSymbol
}

structure DebugSymbol {
    @required
    id: String
    @required
    name: String
    @required
    kind: String
    owner: String
    origin: String
    location: String
}
