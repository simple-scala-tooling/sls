package org.scala.abusers.profiling.runtime

import cats.effect.IOLocal
import cats.effect.unsafe.IORuntime

case class TraceSpan(val spanId: Long)

class LocalSpan(private val _ioLocal: () => IOLocal[Option[TraceSpan]]) {
  private lazy val ioLocal: IOLocal[Option[TraceSpan]] = _ioLocal()
  private lazy val unsafeThreadLocal: ThreadLocal[Option[TraceSpan]] = {
    val fiberLocal = ioLocal.unsafeThreadLocal()

    new ThreadLocal[Option[TraceSpan]] {
      override def initialValue(): Option[TraceSpan] = None

      override def get(): Option[TraceSpan] =
        if (IORuntime.isUnderFiberContext()) fiberLocal.get() else super.get()

      override def set(value: Option[TraceSpan]): Unit =
        if (IORuntime.isUnderFiberContext()) fiberLocal.set(value) else super.set(value)
    }
  }
  def get() = unsafeThreadLocal.get()
  def set(value: Option[TraceSpan]) = unsafeThreadLocal.set(value)
}
