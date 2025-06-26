package ai.masaic.openresponses.tool.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport
import org.springframework.web.reactive.function.client.WebClient

/**
 * Factory for creating authenticated MCP transports
 * This provides a clean API for authentication while using the standard HttpClientSseClientTransport
 */
class HeadersEnabledHttpSseClientTransport {
    fun builder(baseUrl: String): Builder {
        return Builder(baseUrl)
    }

    class Builder(private val baseUrl: String) {
        private var objectMapper: ObjectMapper? = null
        private var headers = emptyMap<String, String>()

        fun objectMapper(objectMapper: ObjectMapper): Builder {
            this.objectMapper = objectMapper
            return this
        }

        fun addHeaders(headers: Map<String, String>): Builder {
            this.headers = headers
            return this
        }

        fun build(): WebFluxSseClientTransport {
            val mapper = objectMapper ?: throw IllegalStateException("ObjectMapper is required")

            // Create an HttpClient.Builder that adds authentication headers to all requests
//            val clientBuilder = HttpClient.newBuilder()
//                .connectTimeout(Duration.ofSeconds(30))

            // Create an HttpRequest.Builder with authentication headers
//            val requestBuilder = HttpRequest.newBuilder()
//                .timeout(Duration.ofSeconds(30))

            // Add authentication headers to the request builder
//            headers.forEach { (name, value) ->
//                requestBuilder.header(name, value)
//            }

            // Use the deprecated but working constructor that accepts custom builders
            // This allows us to inject authentication headers into all requests
//            @Suppress("DEPRECATION")
            val clientBuilder = WebClient.builder().baseUrl(baseUrl.replace("/sse", ""))
//            val headersMap = HttpHeaders.of(headers) { _, _ -> true }
            headers.forEach{clientBuilder.defaultHeader(it.key, it.value)}
            return WebFluxSseClientTransport(clientBuilder, mapper, "/sse")
        }
    }
}
