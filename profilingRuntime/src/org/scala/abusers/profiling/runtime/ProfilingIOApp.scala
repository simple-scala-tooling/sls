package org.scala.abusers.profiling.runtime

import cats.effect.kernel.Resource
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeBuilder
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.IOLocal
import cats.effect.SyncIO
import cats.syntax.all.*
import io.pyroscope.http.Format
import io.pyroscope.javaagent.config.Config as PyroscopeConfig
import io.pyroscope.javaagent.EventType
import io.pyroscope.javaagent.PyroscopeAgent
import io.pyroscope.PyroscopeAsyncProfiler
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.experimental.metrics._
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.sdk.context.Context
import org.typelevel.otel4s.sdk.exporter.otlp.autoconfigure.OtlpExportersAutoConfigure
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import org.typelevel.otel4s.trace.Tracer
import cats.effect.std.Console
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider

object ProfilingIOAppSettings {
  lazy val isEnabled: Boolean =
    sys.env.get("SLS_PROFILING").contains("true") || sys.props.get("sls.profiling").contains("true")
}

trait ProfilingIOApp extends IOApp {
  private lazy val profiler = PyroscopeAsyncProfiler.getAsyncProfiler()

  given console: Console[IO]

  private given localCtx: IOLocal[Context] = IOLocal[Context](Context.root)
    .syncStep(100)
    .flatMap(
      _.leftMap(_ =>
        new Error(
          "Failed to initialize the local context of the IOLocalContextStorageProvider."
        )
      ).liftTo[SyncIO]
    )
    .unsafeRunSync()

  private val threadLocal = localCtx.unsafeThreadLocal()

  private lazy val _runtime = {
    IORuntimeBuilder()
      .transformCompute(ec => ProfilingExecutionContext.wrapExecutionContext(ec, profiler, threadLocal))
      .transformBlocking(ec => ProfilingExecutionContext.wrapExecutionContext(ec, profiler, threadLocal))
      .build()
  }

  override protected def runtime: IORuntime =
    if ProfilingIOAppSettings.isEnabled then _runtime else super.runtime

  def program(using meter: Meter[IO], tracer: Tracer[IO]): Resource[IO, Unit]
  def applicationName: String

  override def run(args: List[String]): IO[ExitCode] =
    OpenTelemetrySdk
      .autoConfigured[IO](_
        .addExportersConfigurer(OtlpExportersAutoConfigure[IO])
        .addTracerProviderCustomizer((builder, _) => builder.addSpanProcessor(new ProfilingSpanProcessor))
      )
      .flatTap(_ => registerPyroscope())
      .use { autoConfigured =>
        val sdk = autoConfigured.sdk
        given MeterProvider[IO] = sdk.meterProvider

        val app = for {
          given Meter[IO]        <- sdk.meterProvider.get(applicationName).toResource
          given Tracer[IO]       <- sdk.tracerProvider.get(applicationName).toResource
          _                      <- IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)
          _                      <- RuntimeMetrics.register[IO]
          _                      <- program
        } yield ()

        app.useForever
      }
      .as(ExitCode.Success)

  private def registerPyroscope(): Resource[IO, Unit] = {
    val acquire = IO.whenA(ProfilingIOAppSettings.isEnabled)( IO.delay {
      PyroscopeAgent.start(
        PyroscopeConfig
          .Builder()
          .setApplicationName(applicationName)
          .setProfilingEvent(EventType.ITIMER)
          .setFormat(Format.JFR)
          .setServerAddress("http://localhost:4040") // Refactor in the future to be configurable
          .build()
      )
    })

    Resource.eval(acquire).void
  }
}
