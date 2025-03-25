package ai.masaic.openresponses.api.config

import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.aop.ObservedAspect
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * Configuration class for logging-related beans.
 * Sets up structured logging and request tracing.
 */
@Configuration
@EnableAspectJAutoProxy
class LoggingConfig {
    /**
     * Creates an observed aspect for automatic instrumentation of methods
     * annotated with @Observed.
     */
    @Bean
    fun observedAspect(observationRegistry: ObservationRegistry): ObservedAspect = ObservedAspect(observationRegistry)

    @Bean
    fun requestIdFilter(): WebFilter = RequestTraceWebFilter()

    /**
     * Web filter that adds unique trace IDs to each request and logs request details.
     * This enables request tracing across asynchronous operations.
     */
    class RequestTraceWebFilter : WebFilter {
        override fun filter(
            exchange: ServerWebExchange,
            chain: WebFilterChain,
        ): Mono<Void> {
            // Check for existing trace IDs (might be provided by Micrometer Tracing)
            val existingTraceId = exchange.request.headers["X-B3-TraceId"]?.firstOrNull()
            val existingSpanId = exchange.request.headers["X-B3-SpanId"]?.firstOrNull()

            // If not present, generate new IDs
            val traceId = existingTraceId ?: UUID.randomUUID().toString().replace("-", "")
            val spanId =
                existingSpanId ?: UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 16)

            // Store in exchange attributes for access during request processing
            exchange.attributes["traceId"] = traceId
            exchange.attributes["spanId"] = spanId

            // Add trace ID to response headers for client-side tracing
            exchange.response.headers.add("X-B3-TraceId", traceId)
            exchange.response.headers.add("X-B3-SpanId", spanId)

            // Log incoming request with trace info
            logger.info {
                "Incoming request - method: ${exchange.request.method}, " +
                    "path: ${exchange.request.path}, " +
                    "client: ${exchange.request.remoteAddress
                        ?.address
                        ?.hostAddress ?: "unknown"}, " +
                    "traceId: $traceId"
            }

            return chain
                .filter(exchange)
                .doOnSuccess {
                    logger.info {
                        "Request completed - " +
                            "status: ${exchange.response.statusCode}, " +
                            "traceId: $traceId, " +
                            "path: ${exchange.request.path}"
                    }
                }.doOnError { throwable ->
                    logger.error {
                        "Request failed - " +
                            "error: ${throwable.message}, " +
                            "traceId: $traceId, " +
                            "path: ${exchange.request.path}"
                    }
                }.contextWrite { ctx ->
                    ctx
                        .put("traceId", traceId)
                        .put("spanId", spanId)
                }
        }
    }
}
