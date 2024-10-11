package arrow.continuations.ktor

import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.continuations.ResourceScope
import io.ktor.server.application.*
import io.ktor.server.engine.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Ktor [ApplicationEngine] as a [Resource]. This [Resource] will gracefully shut down the server
 * When we need to shut down a Ktor service we need to properly take into account a _grace_ period
 * where we still handle requests instead of immediately cancelling any in-flight requests.
 *
 * @param factory Application engine for processing the requests
 * @param port Server listening port. Default is set to 80
 * @param host Host address. Default is set to "0.0.0.0"
 * @param watchPaths specifies path substrings that will be watched for automatic reloading
 * @param configure Ktor server configuration parameters. Only this function is taken into account
 *   for auto-reload.
 * @param preWait preWait a duration to wait before beginning the stop process. During this time,
 *   requests will continue to be accepted. This setting is useful to allow time for the container
 *   to be removed from the load balancer. This is disabled when `io.ktor.development=true`.
 * @param grace grace a duration during which already inflight requests are allowed to continue
 *   before the shutdown process begins.
 * @param timeout timeout a duration after which the server will be forceably shutdown.
 * @param module Represents configured and running web application, capable of handling requests.
 */
suspend fun <
  TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> ResourceScope
  .server(
  factory: ApplicationEngineFactory<TEngine, TConfiguration>,
  port: Int = 80,
  host: String = "0.0.0.0",
  watchPaths: List<String> = listOf(WORKING_DIRECTORY_PATH),
  configure: TConfiguration.() -> Unit = {},
  preWait: Duration = 30.seconds,
  grace: Duration = 500.milliseconds,
  timeout: Duration = 500.milliseconds,
  module: Application.() -> Unit = {}
): EmbeddedServer<TEngine, TConfiguration> =
  install({
    val connectors: Array<EngineConnectorConfig> = arrayOf(
      EngineConnectorBuilder().apply {
        this.port = port
        this.host = host
      }
    )
    val applicationProperties = serverConfig {
      module(body = module)
      this.watchPaths = watchPaths
    }

    embeddedServer(
        factory,
        applicationProperties,
        configure
      ).apply {
        engineConfig.connectors.addAll(connectors)
        start()
      }
  }) { engine, _ ->
    engine.release(preWait, grace, timeout)
  }

/**
 * Ktor [ApplicationEngine] as a [Resource]. This [Resource] will gracefully shut down the server
 * When we need to shut down a Ktor service we need to properly take into account a _grace_ period
 * where we still handle requests instead of immediately cancelling any in-flight requests.
 *
 * @param factory Application engine for processing the requests
 * @param environment definition of the environment where the engine will run
 * @param preWait preWait a duration to wait before beginning the stop process. During this time,
 *   requests will continue to be accepted. This setting is useful to allow time for the container
 *   to be removed from the load balancer. This is disabled when `io.ktor.development=true`.
 * @param grace grace a duration during which already inflight requests are allowed to continue
 *   before the shutdown process begins.
 * @param timeout timeout a duration after which the server will be forceably shutdown.
 */
suspend fun <
  TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> ResourceScope
  .server(
  factory: ApplicationEngineFactory<TEngine, TConfiguration>,
  environment: ApplicationEnvironment,
  configure: TConfiguration.() -> Unit = {},
  preWait: Duration = 30.seconds,
  grace: Duration = 500.milliseconds,
  timeout: Duration = 500.milliseconds
): EmbeddedServer<TEngine, TConfiguration> =
  install({ embeddedServer(factory, environment, configure).apply(EmbeddedServer<TEngine, TConfiguration>::start) }) {
    engine,
    _ ->
    engine.release(preWait, grace, timeout)
  }

private suspend fun <TEngine: ApplicationEngine, TConfiguration: ApplicationEngine.Configuration> EmbeddedServer<TEngine, TConfiguration>.release(
  preWait: Duration,
  grace: Duration,
  timeout: Duration
) {
  if (!application.developmentMode) {
    environment.log.info(
      "prewait delay of ${preWait.inWholeMilliseconds}ms, turn it off using io.ktor.development=true"
    )
    delay(preWait.inWholeMilliseconds)
  }
  environment.log.info("Shutting down HTTP server...")
  stop(grace.inWholeMilliseconds, timeout.inWholeMicroseconds)
  environment.log.info("HTTP server shutdown!")
}

// Ported from Ktor:
// https://github.com/ktorio/ktor/blob/0de7948fbe3f78673f4f90de9c5ea5986691819a/ktor-server/ktor-server-host-common/jvmAndNix/src/io/ktor/server/engine/ServerEngineUtils.kt
internal expect val WORKING_DIRECTORY_PATH: String
