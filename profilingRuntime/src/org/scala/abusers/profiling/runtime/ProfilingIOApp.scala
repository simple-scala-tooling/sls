package org.scala.abusers.profiling.runtime

import cats.effect.IOLocal
import cats.effect.unsafe.IORuntime
import cats.effect.IOApp
import io.pyroscope.PyroscopeAsyncProfiler
import cats.effect.kernel.Resource
import cats.effect.IO
import io.pyroscope.javaagent.PyroscopeAgent
import cats.effect.SyncIO
import org.typelevel.otel4s.sdk.OpenTelemetrySdk
import io.pyroscope.javaagent.config.Config as PyroscopeConfig
import cats.syntax.all.*
import io.pyroscope.javaagent.EventType
import io.pyroscope.http.Format
import org.typelevel.otel4s.sdk.exporter.otlp.autoconfigure.OtlpExportersAutoConfigure
import cats.effect.ExitCode
import cats.effect.unsafe.IORuntimeBuilder
import org.typelevel.otel4s.experimental.metrics._
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.metrics.Meter


trait ProfilingIOApp extends IOApp {
  private lazy val profiler = PyroscopeAsyncProfiler.getAsyncProfiler()
  private lazy val localSpan0: IOLocal[Option[TraceSpan]] = IOLocal[Option[TraceSpan]](None)
    .syncStep(100)
    .flatMap(
      _.leftMap(_ =>
        new Error(
          "Failed to initialize the local context of the IOLocalContextStorageProvider."
        )
      ).liftTo[SyncIO]
    )
    .unsafeRunSync()


  private val localSpan = new LocalSpan(() => localSpan0)
  private val _runtime = {
    IORuntimeBuilder()
      .transformCompute { ec => ProfilingExecutionContext.wrapExecutionContext(ec, profiler, localSpan) }
      .transformBlocking { ec => ProfilingExecutionContext.wrapExecutionContext(ec, profiler, localSpan) }
      .build()
  }

  override protected def runtime: IORuntime = _runtime

  override def run(args: List[String]): IO[ExitCode] =
    OpenTelemetrySdk
      .autoConfigured[IO]( // register OTLP exporters configurer
        _.addExportersConfigurer(OtlpExportersAutoConfigure[IO])
          .addTracerProviderCustomizer((builder, _) =>
              builder.addSpanProcessor(new ProfilingSpanProcessor(localSpan)
        ))
      )
      .flatTap(_ => registerPyroscope()) // register Pyroscope agent
      .use { autoConfigured =>
        val sdk = autoConfigured.sdk
        val app = for {
          given Meter[IO] <- sdk.meterProvider.get(applicationName).toResource
          given Tracer[IO] <- sdk.tracerProvider.get(applicationName).toResource
          _ <- RuntimeMetrics.register[IO]
          _ <- program
        } yield ()

        app.useForever
      }.as(ExitCode.Success)

  def program(using meter: Meter[IO], tracer: Tracer[IO]): Resource[IO, Unit]
  def applicationName: String

  private def registerPyroscope(): Resource[IO, Unit] = {
    val acquire = IO.delay {
      PyroscopeAgent.start(
        PyroscopeConfig.Builder()
          .setApplicationName(applicationName)
          .setProfilingEvent(EventType.ITIMER)
          .setFormat(Format.JFR)
          .setServerAddress("http://localhost:4040") // Refactor in the future to be configurable
          .build()
        )
    }

    Resource.eval(acquire).void
  }
}
