package org.scala.abusers.sls

import cats.effect.kernel.Deferred
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.effect.IO
import fs2.Pipe
import langoustine.lsp.{PreparedNotification => _, PreparedRequest => _, *}
import langoustine.lsp.requests.*
import langoustine.lsp.structures.*

import _root_.fs2.Stream

trait RequestDispatcher {

  def handleCompletion(in: Invocation[CompletionParams, IO]): IO[textDocument.completion.Out]

  def handleDidChange(in: Invocation[DidChangeTextDocumentParams, IO]): IO[Unit]

  def handleInitialized(in: Invocation[InitializedParams, IO]): IO[Unit]
}

case class State()

type CommandResult[T]   = IO[(Option[State], T)]
type NotificationResult = Stream[IO, Option[State]]

trait ServerImpl2 {
  def handleCompletion(
      in: (State, Invocation[textDocument.completion.In, IO])
  ): CommandResult[textDocument.completion.Out]

  def handleNotification(in: Stream[IO, (State, PreparedNotification[IO])]): NotificationResult
}

sealed trait AsyncRequest[F[_]]

trait PreparedRequest[F[_]] extends AsyncRequest[F] {
  type X <: LSPRequest
  type Out = x.Out
  val x: X
  val in: Invocation[x.In, F]
  val replyTo: Deferred[F, Out]
  val supervisor: Supervisor[F]

  def toFull: PreparedRequest.Full[X, F] = PreparedRequest.Full(x, in, replyTo, supervisor)
}
object PreparedRequest {

  case class Full[X <: LSPRequest, F[_]](
      x: X,
      in: Invocation[x.In, F],
      replyTo: Deferred[F, x.Out],
      supervisor: Supervisor[F],
  ) extends AsyncRequest[F]

  def apply[X2 <: LSPRequest, F[_]](
      _x: X2,
      _in: Invocation[_x.In, F],
      _replyTo: Deferred[F, _x.Out],
      _supervisor: Supervisor[F],
  ) =
    new PreparedRequest[F] {
      type X = _x.type
      val x          = _x
      val in         = _in
      val replyTo    = _replyTo
      val supervisor = _supervisor
    }
}

trait PreparedNotification[F[_]] extends AsyncRequest[F] {
  type X <: LSPNotification
  type In = x.In
  val x: X
  val in: Invocation[x.In, F]

}

object PreparedNotification {

  def apply[X2 <: LSPNotification, F[_]](_x: X2, _in: Invocation[_x.In, F]) = new PreparedNotification[F] {
    type X = _x.type
    val x  = _x
    val in = _in
  }
}

extension [X <: LSPRequest](r: X) {
  def matcher: Matcher[X] = new Matcher[X](r)
}

class Matcher[X <: LSPRequest](val expected: X) {
  def unapply[F[_]](
      req: PreparedRequest.Full[X, F]
  ): Option[(Invocation[expected.In, F], Deferred[F, expected.Out], Supervisor[F])] =
    req match {
      case PreparedRequest
            .Full[X, F](`expected`, in: Invocation[expected.In, F], out: Deferred[F, expected.Out], sup) =>
        Some((in, out, sup))
      case _ => None
    }
}

class RequestDispatcherImpl(changeQueue: Queue[IO, AsyncRequest[IO]]) extends RequestDispatcher {

  def handleCompletion(in: Invocation[CompletionParams, IO]): IO[textDocument.completion.Out] =
    Supervisor[IO].use { sup =>
      for {
        reply  <- Deferred[IO, textDocument.completion.Out]
        _      <- changeQueue.offer(PreparedRequest(textDocument.completion, in, reply, sup))
        result <- reply.get
      } yield result
    }

  def handleDidChange(in: Invocation[DidChangeTextDocumentParams, IO]): IO[Unit] =
    changeQueue.offer(PreparedNotification(textDocument.didChange, in))

  def handleInitialized(in: Invocation[InitializedParams, IO]): IO[Unit] =
    changeQueue.offer(PreparedNotification(initialized, in))
}

object RequestDispatcher {

  private val textDocumentCompletion = textDocument.completion.matcher

  def start(server: ServerImpl2, stateRef: Ref[IO, State]): Resource[IO, RequestDispatcher] = {
    val processNotifications: Pipe[IO, (State, AsyncRequest[IO]), Option[State]] = _.collect {
      case (inState, notification: PreparedNotification[IO]) => (inState, notification)
    }
      .through(server.handleNotification)

    val processRequests: Pipe[IO, (State, AsyncRequest[IO]), Option[State]] = _.collect {
      case (inState, request: PreparedRequest[IO]) => (inState, request.toFull)
    }
      .evalMap {
        case (inState, textDocumentCompletion(req, replyTo, supervisor)) =>
          supervisor
            .supervise(server.handleCompletion(inState -> req))
            .flatMap(_.join)
            .map {
              case Succeeded(fa) => fa.flatMap { case (outState, response) => replyTo.complete(response).as(outState) }
              case _             => IO(Option.empty[State])
            }
        case other => sys.error(other.toString)
      }
      .evalMap(identity)

    for {
      changeQueue <- Resource.eval(Queue.unbounded[IO, AsyncRequest[IO]])
      _ <-
        fs2.Stream
          .fromQueueUnterminated(changeQueue)
          .evalMap(request => stateRef.get.map(state => (state, request)))
          .broadcastThrough(
            processNotifications,
            processRequests,
          )
          .evalMap {
            case Some(state) => stateRef.set(state)
            case None        => IO.unit
          }
          .compile
          .drain
          .background

    } yield new RequestDispatcherImpl(changeQueue)
  }
}
