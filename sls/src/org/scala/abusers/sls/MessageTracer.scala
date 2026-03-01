package org.scala.abusers.sls

import cats.effect.IO
import fs2.Pipe
import jsonrpclib.Message
import jsonrpclib.ProtocolError
import org.slf4j.LoggerFactory

class MessageTracer(name: String, envVar: String) {
  private val logger = LoggerFactory.getLogger(name)

  private val enabled: Boolean =
    sys.env.get(envVar)
      .orElse(sys.props.get(envVar.toLowerCase.replace('_', '.')))
      .exists(v => v.equalsIgnoreCase("true") || v == "1")

  def traceIncoming: Pipe[IO, Either[ProtocolError, Message], Either[ProtocolError, Message]] =
    if (enabled) {
      _.evalTap {
        case Right(msg) =>
          IO(logger.trace(s"→ IN: ${MessageTracer.formatMessage(msg)}"))
        case Left(err) =>
          IO(logger.trace(s"→ IN ERROR: $err"))
      }
    } else {
      identity
    }

  def traceOutgoing: Pipe[IO, Message, Message] =
    if (enabled) {
      _.evalTap(msg =>
        IO(logger.trace(s"← OUT: ${MessageTracer.formatMessage(msg)}"))
      )
    } else {
      identity
    }

  def isEnabled: Boolean = enabled
}

object MessageTracer extends MessageTracer(
  "org.scala.abusers.sls.MessageTracer",
  "SLS_TRACE_LSP_MESSAGES"
) {
  private[sls] def formatMessage(msg: Message): String = msg match {
    case jsonrpclib.InputMessage.RequestMessage(method, callId, params) =>
      s"Request[$callId] $method ${params.map(_.data.noSpaces).getOrElse("{}")}"
    case jsonrpclib.InputMessage.NotificationMessage(method, params) =>
      s"Notification $method ${params.map(_.data.noSpaces).getOrElse("{}")}"
    case jsonrpclib.OutputMessage.ResponseMessage(callId, data) =>
      s"Response[$callId] ${data.data.noSpaces}"
    case jsonrpclib.OutputMessage.ErrorMessage(callId, payload) =>
      s"Response[$callId] ERROR: $payload"
  }
}

object CspMessageTracer extends MessageTracer(
  "org.scala.abusers.sls.CspMessageTracer",
  "SLS_TRACE_CSP_MESSAGES"
)
