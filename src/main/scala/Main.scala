import zio._
import zio.metrics.{Metric, MetricKeyType}
import zio.http._
import zio.http.Method
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.connectors.{MetricsConfig, prometheus}
import zio.metrics.jvm.DefaultJvmMetrics

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault {

  val requestCounter = Metric.counter("request_count").fromConst(1)

  val requestTime =
    Metric.histogram("request_time", MetricKeyType.Histogram.Boundaries.linear(0, 500, 21))

  val histogram =
    Metric.histogram("histogram", MetricKeyType.Histogram.Boundaries.linear(0, 10, 11))


  val ok = Random.nextDoubleBetween(0.0d, 120.0d) @@ histogram

  val executionTime: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      zio.timed.flatMap {
        t =>
          val executionMillis = TimeUnit.NANOSECONDS.toMillis(t._1.get(ChronoUnit.NANOS)).toDouble
          (ZIO.succeed(executionMillis) @@ requestTime) *> ZIO.succeed(t._2)
      }
  }

    def memoryUsage: ZIO[Any, Nothing, Double] = {
    import java.lang.Runtime._
    ZIO
      .succeed(getRuntime.totalMemory() - getRuntime.freeMemory())
      .map(_ / (1024.0 * 1024.0)) @@ Metric.gauge("memory_usage")
  }

  val zap = (for {
    _ <- memoryUsage
    time <- Clock.currentDateTime
  } yield Response.text(s"$time\t/foo API called")) @@ executionTime

  private val httpApp =
    Http
      .collectZIO[Request] {
        case Method.GET -> root / "metrics" =>
          ZIO.serviceWithZIO[PrometheusPublisher](_.get.map(Response.text))
        case Method.GET -> root / "foo" => zap
      }

  override def run = Server
    .serve(httpApp)
    .provide(
      // ZIO Http default server layer, default port: 8080
      Server.default,
      // The prometheus reporting layer
      prometheus.prometheusLayer,
      prometheus.publisherLayer,
      // Interval for polling metrics
      ZLayer.succeed(MetricsConfig(5.seconds)),
      DefaultJvmMetrics.live.unit
    )
}