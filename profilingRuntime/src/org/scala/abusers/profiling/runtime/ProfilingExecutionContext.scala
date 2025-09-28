package cats.effect.org.scala.abusers.profiling.runtime

import scala.concurrent.ExecutionContext
import io.pyroscope.vendor.one.profiler.AsyncProfiler
import org.typelevel.otel4s.sdk.context.Context
import org.typelevel.otel4s.sdk.trace.SdkContextKeys
import org.typelevel.otel4s.sdk.context.TraceContext

object ProfilingExecutionContext {

  def wrapExecutionContext(ec: ExecutionContext, profiler: AsyncProfiler, localContext: ThreadLocal[Context]): ExecutionContext = {
    new ExecutionContext {
      def execute(runnable: Runnable): Unit = {

        ec.execute(new Runnable {
          val ctx = localContext.get()
          def run(): Unit = {

            val spanOpt = ctx
              .get(SdkContextKeys.SpanContextKey)
              .filter(_.isValid)
              .map { ctx =>
                TraceContext(
                  ctx.traceId,
                  ctx.spanId,
                  ctx.isSampled
                )
              }

            spanOpt.foreach { span =>
                profiler.setTracingContext(span.spanId.toLong(signed = false), 0)
            }

            try {
              runnable.run()
            } finally {
              spanOpt.foreach { _ => profiler.setTracingContext(0, 0) }
            }
          }
        })
      }

      def reportFailure(cause: Throwable): Unit = ec.reportFailure(cause)
    }
  }


}
