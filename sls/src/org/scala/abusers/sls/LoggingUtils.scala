package org.scala.abusers.sls

import cats.effect.IO

object LoggingUtils {
  extension (client: SlsLanguageClient[IO]) {
    def sendMessage(msg: String): IO[Unit] =
      client.windowLogMessage(lsp.LogMessageParams(lsp.MessageType.INFO, msg))
      // back.notification(
      //   requests.window.showMessage,
      //   ShowMessageParams(enumerations.MessageType.Info, msg),
      // ) *> logMessage(msg)

    def logMessage(msg: String): IO[Unit] =
      client.windowLogMessage(lsp.LogMessageParams(lsp.MessageType.INFO, msg))

    def logDebug(msg: String): IO[Unit] =
      client.windowLogMessage(lsp.LogMessageParams(lsp.MessageType.LOG, msg))
  }
}
