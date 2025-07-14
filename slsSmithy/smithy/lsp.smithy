$version: "2.0"

namespace org.scala.abusers.sls

use jsonrpclib#jsonRpc

@jsonRpc
service SlsLanguageClient {
    operations: [
      lsp#Initialized,
      lsp#WindowLogMessage,
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

    // } yield SlsLanguageServer[IO]
      // .create[IO]
      // .handleRequest(initialize)(impl.handleInitialize(steward, bspClientDeferred))
      // .handleNotification(textDocument.didOpen)(impl.handleDidOpen)
      // .handleNotification(textDocument.didClose)(impl.handleDidClose)
      // .handleNotification(textDocument.didChange)(impl.handleDidChange)
      // .handleNotification(textDocument.didSave)(impl.handleDidSave)
      // .handleNotification(initialized)(impl.handleInitialized)
      // .handleRequest(textDocument.completion)(impl.handleCompletion)
      // .handleRequest(textDocument.hover)(impl.handleHover)
      // .handleRequest(textDocument.signatureHelp)(impl.handleSignatureHelp)
      // .handleRequest(textDocument.definition)(impl.handleDefinition)
      // .handleRequest(textDocument.inlayHint)(impl.handleInlayHints)
  // .handleRequest(textDocument.inlayHint.resolve)(impl.handleInlayHintsResolve)
