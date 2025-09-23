package org.scala.abusers.profiling.runtime

import scala.concurrent.ExecutionContext
import io.pyroscope.vendor.one.profiler.AsyncProfiler

object ProfilingExecutionContext {

  def wrapExecutionContext(ec: ExecutionContext, profiler: AsyncProfiler, localSpan: LocalSpan): ExecutionContext = {
    new ExecutionContext {
      def execute(runnable: Runnable): Unit = {
        val spanOpt = localSpan.get()

        ec.execute(new Runnable {
          def run(): Unit = {

            spanOpt.foreach { span =>
              profiler.setTracingContext(span.spanId, 0)
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
