package org.scala.abusers.pc

// import coursier.util.Gather
// import coursier.util.Monad
// import coursier.util.Sync

/* Copied from coursier-cats-interop */
object CoursiercatsInterop {

//   implicit def coursierMonadFromCats[F[_]](implicit M: _root_.cats.Monad[F]): Monad[F] =
//     new Monad[F] {
//       def point[A](a: A)                       = M.pure(a)
//       def bind[A, B](elem: F[A])(f: A => F[B]) = M.flatMap(elem)(f)
//     }

//   implicit def coursierGatherFromCats[F[_], F0[_]](implicit
//       N: _root_.cats.Monad[F],
//       cs: _root_.cats.Parallel.Aux[F, F0],
//   ): Gather[F] =
//     new Gather[F] {
//       def point[A](a: A)                       = N.pure(a)
//       def bind[A, B](elem: F[A])(f: A => F[B]) = N.flatMap(elem)(f)
//       def gather[A](elems: Seq[F[A]]) =
//         N.map(_root_.cats.Parallel.parSequence(elems.toVector))(_.toSeq)
//     }

//   implicit def coursierSyncFromCats[F[_], F0[_]](implicit
//       N: cats.effect.Async[F],
//       par: cats.Parallel.Aux[F, F0],
//   ): Sync[F] =
//     new Sync[F] {
//       def point[A](a: A): F[A] =
//         a.pure[F]
//       def delay[A](a: => A): F[A] =
//         N.delay(a)
//       override def fromAttempt[A](a: Either[Throwable, A]): F[A] =
//         N.fromEither(a)
//       def handle[A](a: F[A])(f: PartialFunction[Throwable, A]): F[A] =
//         a.recover(f)
//       def schedule[A](pool: ExecutorService)(f: => A): F[A] = {
//         val ec0 = pool match {
//           case eces: ExecutionContextExecutorService => eces
//           case _                                     =>
//             // FIXME Is this instantiation costly? Cache it?
//             ExecutionContext.fromExecutorService(pool)
//         }
//         N.evalOn(N.delay(f), ec0)
//       }

//       def gather[A](elems: Seq[F[A]]): F[Seq[A]] =
//         N.map(_root_.cats.Parallel.parSequence(elems.toVector))(_.toSeq)
//       def bind[A, B](elem: F[A])(f: A => F[B]): F[B] =
//         elem.flatMap(f)
//     }
}
