package org.scala.abusers.profiling.runtime


import org.typelevel.otel4s.trace.SpanOps
import org.typelevel.otel4s.trace.Span
import cats.effect.IO

object ProfilingOps {
  extension (ops: SpanOps[IO]) {
    def profilingUse[A](f: Span[IO] => IO[A]): IO[A] =
      if ProfilingIOAppSettings.isEnabled then
        ops.use(span => (IO.cede *> f(span)).guarantee(IO.cede))
      else ops.use(f)

    inline def profilingSurround[A](fa: IO[A]): IO[A] = profilingUse(_ => fa)
  }
}
