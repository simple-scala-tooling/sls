$version: "2.0"

namespace org.scala.abusers.sls

use jsonrpclib#jsonRpc

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
    ]
}
