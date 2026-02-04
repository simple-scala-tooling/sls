package org.scala.abusers.sls

import cats.effect.IO
import fs2.Pipe
import jsonrpclib.Message
import jsonrpclib.ProtocolError
import org.slf4j.LoggerFactory

object MessageTracer {
  private val logger = LoggerFactory.getLogger(getClass)

  // Enable/disable via environment variable or system property
  private val enabled: Boolean =
    sys.env.get("SLS_TRACE_MESSAGES")
      .orElse(sys.props.get("sls.trace.messages"))
      .exists(v => v.equalsIgnoreCase("true") || v == "1")

  def traceIncoming: Pipe[IO, Either[ProtocolError, Message], Either[ProtocolError, Message]] =
    if (enabled) {
      _.evalTap {
        case Right(msg) =>
          IO(logger.trace(s"IN: ${formatMessage(msg)}"))
        case Left(err) =>
          IO(logger.trace(s"IN ERROR: $err"))
      }
    } else {
      identity
    }

  def traceOutgoing: Pipe[IO, Message, Message] =
    if (enabled) {
      _.evalTap(msg =>
        IO(logger.trace(s"← OUT: ${formatMessage(msg)}"))
      )
    } else {
      identity
    }

  private def formatMessage(msg: Message): String = msg match {
    case jsonrpclib.InputMessage.RequestMessage(method, callId, params) =>
      s"Request[$callId] $method ${params.map(_.data.noSpaces).getOrElse("{}")}"
    case jsonrpclib.InputMessage.NotificationMessage(method, params) =>
      s"Notification $method ${params.map(_.data.noSpaces).getOrElse("{}")}"
    case jsonrpclib.OutputMessage.ResponseMessage(callId, data) =>
      s"Response[$callId] ${data.data.noSpaces}"
    case jsonrpclib.OutputMessage.ErrorMessage(callId, payload) =>
      s"Response[$callId] ERROR: $payload"
  }

  def isEnabled: Boolean = enabled
}
