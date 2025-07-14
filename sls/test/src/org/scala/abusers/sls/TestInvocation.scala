package org.scala.abusers.sls

// import cats.effect.IO
// import cats.MonadThrow
// import jsonrpclib.Monadic
// import langoustine.lsp.requests.*
// import langoustine.lsp.Communicate
// import langoustine.lsp.Invocation

// import scala.collection.mutable.ListBuffer

// given [F[_]](using MonadThrow[F]): Monadic[F] with {
//   def doFlatMap[A, B](fa: F[A])(f: A => F[B]): F[B]   = MonadThrow[F].flatMap(fa)(f)
//   def doPure[A](a: A): F[A]                           = MonadThrow[F].pure(a)
//   def doAttempt[A](fa: F[A]): F[Either[Throwable, A]] = MonadThrow[F].attempt(fa)
//   def doRaiseError[A](e: Throwable): F[A]             = MonadThrow[F].raiseError(e)
// }

// case class TestClient(log: weaver.Log[IO]) {
//   val communicate                            = TestCommunicate(log)
//   def input[A](params: A): Invocation[A, IO] = TestInvocation(params)

//   private case class TestInvocation[A](params: A) extends Invocation[A, IO] {
//     def toClient = communicate
//   }
// }

// class TestCommunicate(log: weaver.Log[IO]) extends Communicate[IO] {
//   val notifications = ListBuffer()
//   def notification[X <: LSPNotification](notif: X, in: notif.In): IO[Unit] =
//     log.info(in.toString)

//   def request[X <: LSPRequest](req: X, in: req.In): IO[req.Out] =
//     Communicate.drop[IO].request(req, in)

//   def shutdown: IO[Unit] = IO.unit
// }
