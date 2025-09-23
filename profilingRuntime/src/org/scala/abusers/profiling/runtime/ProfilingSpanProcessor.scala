package org.scala.abusers.profiling.runtime

import cats.effect.IO
import org.typelevel.otel4s.sdk.trace.processor.SpanProcessor
import org.typelevel.otel4s.sdk.trace.processor.SpanProcessor.OnEnd
import org.typelevel.otel4s.sdk.trace.processor.SpanProcessor.OnStart
import org.typelevel.otel4s.trace.SpanContext
import org.typelevel.otel4s.sdk.trace.SpanRef
import org.typelevel.otel4s.sdk.trace.data.SpanData
import org.typelevel.otel4s.Attribute

class ProfilingSpanProcessor(localSpan: LocalSpan) extends SpanProcessor[IO] {

  override def name: String = "ProfilingSpanProcessor"

  override def onStart: OnStart[IO] = new OnStart[IO] {
    override def apply(parentContext: Option[SpanContext], span: SpanRef[IO]): IO[Unit] = {
      val spanIdStr = span.context.spanIdHex
      val spanId = java.lang.Long.parseUnsignedLong(spanIdStr, 16)
      for {
        _ <- span.addAttributes(Seq(Attribute("pyroscope.profile.id", spanIdStr)))
        _ <- IO(localSpan.set(Some(TraceSpan(spanId))))
      } yield ()
    }
  }

  override def onEnd: OnEnd[IO] = new OnEnd[IO] {
    override def apply(span: SpanData): IO[Unit] = IO(localSpan.set(None))
  }

  override def forceFlush: IO[Unit] = IO.unit
}
