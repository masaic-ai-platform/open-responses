import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.Converter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.time.Duration


// Custom exception to signal session expiration
class SessionNotFoundException : RuntimeException("MCP session not found or expired")

/**
 * Low-level HTTP+SSE transport for MCP messages, with support for capturing session headers.
 */
class HttpSseTransport(
    private val endpoint: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Sends a JSON-RPC message via HTTP POST and returns the parsed body and response headers.
     * Throws SessionNotFoundException if server responds 404 when sessionId was present.
     */
    fun sendWithHeaders(
        payload: Any,
        sessionId: String? = null,
    ): Pair<Any?, Headers> {
        val json = mapper.writeValueAsString(payload)
        val request = Request
            .Builder()
            .url(endpoint)
            .post(json.toRequestBody(jsonMediaType))
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .build()

        client.newCall(request).execute().use { resp ->
            val headers = resp.headers
            val code = resp.code

            // Session expired: must reinitialize
            if (sessionId != null && code == 404) {
                throw SessionNotFoundException()
            }

            // Pure notifications/responses
            if (code == 202 && resp.body?.contentLength() == 0L) {
                return Pair(null, headers)
            }

            // Regular JSON body
            val raw = resp.body?.string().orEmpty()
            val parsed = if (raw.isNotBlank()) mapper.readValue(raw, Any::class.java) else null
            return Pair(parsed, headers)
        }
    }

    /**
     * Sends a JSON-RPC request, optionally streaming SSE events via onEvent.
     * Returns the raw string response if non-streamed.
     */
    fun send(
        payload: Any,
        sessionId: String? = null,
        onEvent: ((Any) -> Unit)? = null,
    ): String? {
        val json = mapper.writeValueAsString(payload)
        val requestBuilder = Request
            .Builder()
            .url(endpoint)
            .post(json.toRequestBody(jsonMediaType))
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val code = resp.code
            val ct = resp.header("Content-Type").orEmpty()

            // Session expired
            if (sessionId != null && code == 404) {
                throw SessionNotFoundException()
            }

            if (code == 202 && resp.body?.contentLength() == 0L) return null
            if (code in 400..503) {
                throw Exception("MCP call failed: HTTP $code - ${resp.body?.string()}")
            }

            if (ct.startsWith("text/event-stream") && onEvent != null) {
                openSse(requestBuilder.get().build(), onEvent)
                return null
            }

            return resp.body!!.string()
        }
    }

    /**
     * Opens an SSE stream via HTTP GET, including session header.
     */
    fun subscribe(
        sessionId: String,
        onMessage: (Any) -> Unit,
    ) {
        val request = Request
            .Builder()
            .url(endpoint)
            .get()
            .header("Accept", "text/event-stream")
            .header("Mcp-Session-Id", sessionId)
            .build()

        openSse(request, onMessage)
    }

    /**
     * Terminates the session via HTTP DELETE.
     */
    fun deleteSession(sessionId: String): Int {
        val request = Request
            .Builder()
            .url(endpoint)
            .delete()
            .header("Mcp-Session-Id", sessionId)
            .build()

        client.newCall(request).execute().use { resp ->
            return resp.code
        }
    }

    private fun openSse(
        request: Request,
        onMessage: (Any) -> Unit,
    ) {
        val factory = EventSources.createFactory(client)
        factory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    source: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    mapper.readValue(data, Any::class.java)?.let(onMessage)
                }

                override fun onFailure(
                    source: EventSource,
                    t: Throwable?,
                    response: okhttp3.Response?,
                ) {
                    source.cancel()
                }
            }
        )
    }
}

/**
 * Synchronous MCP client with session management support.
 */
class McpSyncClient private constructor(
    private val transport: HttpSseTransport,
    private val capabilities: ClientCapabilities,
) {
    private var sessionId: String? = null
    private val mapper = jacksonObjectMapper()

    init {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    companion object {
        @JvmStatic
        fun sync(transport: HttpSseTransport) = Builder(transport)
    }

    class Builder(
        private val transport: HttpSseTransport,
    ) {
        private var timeout: Duration = Duration.ofSeconds(30)
        private var capabilities: ClientCapabilities = ClientCapabilities()

        fun requestTimeout(d: Duration) = apply { timeout = d }
        fun capabilities(cap: ClientCapabilities) = apply { capabilities = cap }
        fun build() = McpSyncClient(transport, capabilities)
    }

    /**
     * Initializes the MCP session. Handles stateful session establishment.
     */
    fun initialize() {
        // 1. Send InitializeRequest without session header
        val initReq = mapOf(
            "jsonrpc" to "2.0",
            "method" to "initialize",
            "id" to 1,
            "params" to mapOf(
//                "protocolVersion" to "2025-03-26",
                "protocolVersion" to "2024-11-05",
//                "capabilities" to capabilities,
                "capabilities" to emptyMap<String, Any>(),
//                        mapOf(
//                    "roots" to mapOf("listChanged" to true)
////                    "sampling" to emptyMap()
//                ),
                "clientInfo" to mapOf(
                    "name" to "ExampleClient",
                    "version" to "1.0.0"
                )
            )
        )
        val (parsedBody, headers) = transport.sendWithHeaders(initReq)

        // 2. Extract session ID from header or body
        sessionId = headers["Mcp-Session-Id"]
            ?: (parsedBody as? Map<*, *>)?.get("result")
                ?.let { (it as? Map<*, *>)?.get("sessionId") as? String }

        // 3. Send InitializedNotification as JSON-RPC notification
        val initNotif = mapOf(
            "jsonrpc" to "2.0",
            "method" to "initialized",
            "params" to emptyMap<String, Any>()
        )
        transport.sendWithHeaders(initNotif, sessionId)
    }

    /**
     * Fetches available tools, with automatic session reinitialization on expiry.
     */
    fun listTools(): List<ToolSpecification> = withSessionRetry {
        val resp = transport.send(
            mapOf("jsonrpc" to "2.0", "method" to "tools/list", "id" to 2),
            sessionId
        ) ?: error("Empty response from tools/list")
        Converter.convert(mapper.readTree(resp))
    }

    /**
     * Invokes a tool call, with automatic session reinitialization on expiry.
     */
    fun callTool(request: CallToolRequest): String = withSessionRetry {
        transport.send(request.toRpc(3), sessionId) ?: error("Empty response from tool call")
    }

    /**
     * Sends shutdown request and terminates the session via HTTP DELETE.
     */
    fun closeGracefully() = withSessionRetry {
        // Shutdown RPC
        transport.send(
            mapOf("jsonrpc" to "2.0", "method" to "shutdown", "id" to 10),
            sessionId
        )

        // Terminate session
        sessionId?.let {
            val code = transport.deleteSession(it)
            if (code != 200 && code != 204 && code != 405) {
                throw Exception("Session termination failed: HTTP $code")
            }
            sessionId = null
        }
    }

    /**
     * Listens for server-initiated messages via SSE. Requires a valid session.
     */
    fun subscribe(onMessage: (Any) -> Unit) {
        sessionId?.let { transport.subscribe(it, onMessage) }
            ?: throw IllegalStateException("Session not initialized")
    }

    /**
     * Helper to retry a block once if session expired (HTTP 404).
     */
    private fun <T> withSessionRetry(block: () -> T): T {
        return try {
            block()
        } catch (e: SessionNotFoundException) {
            sessionId = null
            initialize()
            block()
        }
    }
}

/**
 * Supported client capabilities.
 */
data class ClientCapabilities(
    val roots: Boolean = false,
    val sampling: Boolean = false,
) {
    data class Builder(
        var roots: Boolean = false,
        var sampling: Boolean = false,
    ) {
        fun roots(enable: Boolean = true) = apply { this.roots = enable }
        fun sampling(enable: Boolean = true) = apply { this.sampling = enable }
        fun build() = ClientCapabilities(roots, sampling)
    }
}

// Helper data classes for RPC
data class CallToolRequest(
    val name: String,
    val params: JsonNode,
) {
    fun toRpc(id: Int) = mapOf(
        "jsonrpc" to "2.0",
        "method" to "tools/call",
        "id" to id,
        "params" to mapOf("name" to name, "arguments" to params)
    )
}

fun main() {
    // 1. Configure endpoint and transport
    val endpoint = "https://mcp.deepwiki.com/mcp"
    val transport = HttpSseTransport(endpoint)

    // 2. Build the sync client with a 60s timeout and whatever capabilities you like
    val client = McpSyncClient
        .sync(transport)
        .requestTimeout(Duration.ofSeconds(60))
        .capabilities(ClientCapabilities.Builder().roots().sampling().build())
        .build()

    try {
        // 3. Initialize session
        client.initialize()
        println("Session initialized.")

        // 4. List available tools
        val tools = client.listTools()
        println("Available tools:")
        tools.forEach { println(" - ${it.name()}: ${it.description()}") }
//
//        // 5. Make a simple echo-style call (replace with a real tool name/params)
//        val params: ObjectNode = JsonNodeFactory.instance.objectNode()
//            .put("message", "Hello from test main!")
//        val echoResp = client.callTool(CallToolRequest("echo", params))
//        println("Echo tool response: $echoResp")
//
//        // 6. Spin up a subscriber thread for server-initiated events
//        thread(start = true) {
//            client.subscribe { event ->
//                println("Received server event: $event")
//            }
//        }
//
//        // 7. Let the subscriber run for 5 seconds
//        Thread.sleep(5_000)

    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        // 8. Shutdown RPC and terminate session
        client.closeGracefully()
        println("Client shut down.")
    }
}
