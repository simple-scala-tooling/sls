package org.scala.abusers.sls.integration.utils

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import lsp.*
import org.scala.abusers.sls.SlsLanguageClient

class TestLSPClient(
    diagnosticsRef: Ref[IO, List[PublishDiagnosticsParams]],
    messagesRef: Ref[IO, List[ShowMessageParams]],
    logMessagesRef: Ref[IO, List[LogMessageParams]]
) extends SlsLanguageClient[IO] {

  def textDocumentPublishDiagnostics(params: PublishDiagnosticsParams): IO[Unit] =
    diagnosticsRef.update(_ :+ params)

  def windowShowMessage(params: ShowMessageParams): IO[Unit] =
    messagesRef.update(_ :+ params)

  def windowLogMessage(params: LogMessageParams): IO[Unit] =
    logMessagesRef.update(_ :+ params)

  def initialized(params: InitializedParams): IO[Unit] =
    IO.unit

  def getPublishedDiagnostics: IO[List[PublishDiagnosticsParams]] =
    diagnosticsRef.get

  def getShowMessages: IO[List[ShowMessageParams]] =
    messagesRef.get

  def getLogMessages: IO[List[LogMessageParams]] =
    logMessagesRef.get

  def clearAll: IO[Unit] =
    diagnosticsRef.set(List.empty) *>
      messagesRef.set(List.empty) *>
      logMessagesRef.set(List.empty)
}

object TestLSPClient {
  def create: IO[TestLSPClient] =
    for {
      diagnosticsRef <- Ref.of[IO, List[PublishDiagnosticsParams]](List.empty)
      messagesRef    <- Ref.of[IO, List[ShowMessageParams]](List.empty)
      logMessagesRef <- Ref.of[IO, List[LogMessageParams]](List.empty)
    } yield TestLSPClient(diagnosticsRef, messagesRef, logMessagesRef)
}