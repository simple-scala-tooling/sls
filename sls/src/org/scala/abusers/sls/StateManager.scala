package org.scala.abusers.sls

import bsp.CompileParams
import cats.effect.std.Mutex
import cats.effect.IO

import java.net.URI

object StateManager {

  def instance(
      lspClient: SlsLanguageClient[IO],
      textDocumentSyncManager: TextDocumentSyncManager,
      bspStateManager: BspStateManager,
  ): IO[StateManager] =
    Mutex[IO].map(StateManager(lspClient, textDocumentSyncManager, bspStateManager, _))

}

/** Class created to synchronize all access to the state of bsp / textDocument Original problem was that by separating
  * both of them, didOpen was called in sequence which I believe could end up by some other request stealing the lock.
  *
  * Before we didn't synchronize the state modification, and inlayHint request was sent instantly and it accessed
  * [[bspStateManager.get]] that was not yet completed ([[ MapRef ]] does not lock on get)
  *
  * By unifying this in single manager, we can control what has to be synchronized.
  */
class StateManager(
    lspClient: SlsLanguageClient[IO],
    textDocumentSyncManager: TextDocumentSyncManager,
    bspStateManager: BspStateManager,
    mutex: Mutex[IO],
) {
  def didOpen(params: lsp.DidOpenTextDocumentParams): IO[Unit] =
    mutex.lock.surround {
      textDocumentSyncManager.didOpen(params) *> bspStateManager.didOpen(lspClient, params)
    }

  def didChange(params: lsp.DidChangeTextDocumentParams) =
    mutex.lock.surround {
      textDocumentSyncManager.didChange(params)
    }

  def didClose(params: lsp.DidCloseTextDocumentParams): IO[Unit] =
    mutex.lock.surround {
      textDocumentSyncManager.didClose(params)
    }

  def didSave(params: lsp.DidSaveTextDocumentParams): IO[Unit] =
    mutex.lock
      .surround {
        for {
          _    <- textDocumentSyncManager.didSave(params)
          info <- bspStateManager.get(URI(params.textDocument.uri))
        } yield info
      }
      .flatMap { info =>
        bspStateManager.bspServer.generic.buildTargetCompile(CompileParams(targets = List(info.buildTarget.id)))
      }
      .void

  def getDocumentState(uri: URI): IO[DocumentState] =
    mutex.lock.surround {
      textDocumentSyncManager.get(uri)
    }

  def getBuildTargetInformation(uri: URI): IO[ScalaBuildTargetInformation] =
    mutex.lock.surround {
      bspStateManager.get(uri)
    }

  def importBuild: IO[Unit] =
    mutex.lock.surround {
      bspStateManager.importBuild
    }

}
