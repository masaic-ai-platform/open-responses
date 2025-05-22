package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.tool.ToolDefinition
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.Converter
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import mu.KotlinLogging
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.time.Duration
import java.util.concurrent.*

class McpClient {
    private val log = KotlinLogging.logger {}
    private var customClient: McpSyncClient? = null
    private var defaultMcpClient: DefaultMcpClient? = null
    private val mapper = jacksonObjectMapper()

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 20L
    }

    fun init(
        serverName: String,
        mcpServer: MCPServer,
        transportType: String = "http",
    ): McpClient {
        when (transportType) {
            "http" -> {
                val transport =
                    HttpSseTransport(
                        mcpServer.url ?: throw IllegalStateException("for remote http server, url is mandatory."),
                    )
                customClient =
                    McpSyncClient
                        .sync(transport)
                        .requestTimeout(Duration.ofSeconds(60 * 1000))
                        .capabilities(
                            ClientCapabilities
                                .Builder()
                                .roots()
                                .sampling()
                                .build(),
                        ).build()
                log.info("MCP HTTP client connected for $serverName server at: ${mcpServer.url}")
            }

            "stdio" -> {
                val command = buildCommand(mcpServer)
                log.info("Command to start server will be: ${command.joinToString(" ")}")

                // Create an executor with a single thread dedicated to this blocking operation
                val executor = Executors.newSingleThreadExecutor()

                defaultMcpClient =
                    try {
                        val future: Future<DefaultMcpClient> =
                            executor.submit(
                                Callable {
                                    // Build the transport (this should be fast or already cooperative)
                                    val transport: McpTransport =
                                        StdioMcpTransport
                                            .Builder()
                                            .command(command)
                                            .logEvents(true)
                                            .build()

                                    // This call is blocking and may run infinitely if not cooperative.
                                    DefaultMcpClient.Builder().transport(transport).build()
                                },
                            )

                        // Try to get the result within timeout period
                        future.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        // Cancel the task if it's still running
                        throw IllegalStateException("Timed out while connecting to MCP server $serverName", e)
                    } finally {
                        executor.shutdownNow()
                    }

                log.info("MCP StdIO client connected for $serverName server with command: ${command.joinToString(" ")}")
            }

            else -> throw UnsupportedOperationException("transportType can be only http or stdio")
        }

        return this
    }

    fun listTools(serverId: String): List<McpToolDefinition> {
        val toolSpecs = defaultMcpClient?.listTools() ?: customClient?.listTools()
        return toolSpecs?.map { toolSpec ->
            val tool =
                McpToolDefinition(
                    name = toolSpec.name(),
                    description = toolSpec.description() ?: toolSpec.name(),
                    parameters = toolSpec.parameters(),
                    mcpServerInfo = MCPServerInfo(serverId),
                )
            log.info("Adding tool: $tool")
            tool
        } ?: emptyList()
    }

    fun executeTool(
        tool: ToolDefinition,
        arguments: String,
    ): String? {
        return customClient?.callTool(CallToolRequest(tool.name, mapper.readTree(arguments)))
            ?: return defaultMcpClient?.executeTool(
                ToolExecutionRequest
                    .builder()
                    .name(tool.name)
                    .arguments(arguments)
                    .build(),
            )
    }

    /**
     * Builds the command list for starting an MCP server via standard I/O.
     *
     * @param mcpServer Server configuration
     * @return List of command arguments
     */
    private fun buildCommand(mcpServer: MCPServer): List<String> =
        buildList {
            mcpServer.command?.let { add(it) }
            mcpServer.args.forEach { arg ->
                val envVar = mcpServer.env[arg]
                val envValue = envVar?.let { System.getenv(it) ?: it }
                add(envValue?.let { "$arg=$it" } ?: arg)
            }
            add("2>&1")
        }

    fun close() {
        customClient?.closeGracefully() ?: defaultMcpClient?.close()
    }
}

/**
 * Low-level HTTP+SSE transport for MCP messages, with support for capturing session headers.
 */
class HttpSseTransport(
    private val endpoint: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val mapper = jacksonObjectMapper()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Sends a JSON-RPC message via HTTP POST and returns the parsed body and response headers.
     */
    fun sendWithHeaders(
        payload: Any,
        sessionId: String? = null,
    ): Pair<Any?, Headers> {
        val json = mapper.writeValueAsString(payload)
        val request =
            Request
                .Builder()
                .url(endpoint)
                .post(json.toRequestBody(jsonMediaType))
                .header("Accept", "application/json, text/event-stream")
                .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
                .build()

        client.newCall(request).execute().use { resp ->
            val headers = resp.headers
            val code = resp.code
            val ct = resp.header("Content-Type").orEmpty()

            // Pure notifications/responses
            if (code == 202 && resp.body?.contentLength() == 0L) {
                return Pair(null, headers)
            }

            // Regular JSON body
            val raw = resp.body?.string().orEmpty()
            val parsed = if (raw.isNotBlank()) mapper.readValue<Any>(raw) else null
            return Pair(parsed, headers)
        }
    }

    /**
     * Sends a JSON-RPC request, optionally streaming SSE events via onEvent.
     */
    fun send(
        payload: Any,
        sessionId: String? = null,
        onEvent: ((Any) -> Unit)? = null,
    ): String? {
        val json = mapper.writeValueAsString(payload)
        val request =
            Request
                .Builder()
                .url(endpoint)
                .post(json.toRequestBody(jsonMediaType))
                .header("Accept", "application/json, text/event-stream")
                .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
                .build()

        client.newCall(request).execute().use { resp ->
            val code = resp.code
            val ct = resp.header("Content-Type").orEmpty()

            if (code == 202 && resp.body?.contentLength() == 0L) return null

            if (ct.startsWith("text/event-stream") && onEvent != null) {
                openSse(request, onEvent)
                return null
            }

            return resp.body!!.string()
        }
    }

    private fun openSse(
        request: Request,
        onMessage: (Any) -> Unit,
    ) {
        val factory = EventSources.createFactory(client)
        factory.newEventSource(
            request.newBuilder().get().build(),
            object : EventSourceListener() {
                override fun onEvent(
                    source: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    mapper.readValue<Any>(data)?.let(onMessage)
                }

                override fun onFailure(
                    source: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    source.cancel()
                }
            },
        )
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

/**
 * Synchronous MCP client.
 */
class McpSyncClient private constructor(
    private val transport: HttpSseTransport,
    private val timeout: Duration,
    private val capabilities: ClientCapabilities,
    private val samplingHandler: ((CreateMessageRequest) -> CreateMessageResult)?,
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
        private var samplingHandler: ((CreateMessageRequest) -> CreateMessageResult)? = null

        fun requestTimeout(d: Duration) = apply { timeout = d }

        fun capabilities(cap: ClientCapabilities) = apply { capabilities = cap }

        fun sampling(handler: (CreateMessageRequest) -> CreateMessageResult) = apply { samplingHandler = handler }

        fun build() = McpSyncClient(transport, timeout, capabilities, samplingHandler)
    }

    /**
     * Initializes the MCP session. If the server returns an Mcp-Session-Id header,
     * that value is used. Otherwise, no session ID is stored, and subsequent calls
     * are made without a session header.
     */
    fun initialize() {
        val initReq =
            mapOf(
                "jsonrpc" to "2.0",
                "method" to "initialize",
                "id" to 1,
                "params" to
                    mapOf(
                        "protocolVersion" to "2025-03-26",
                        "capabilities" to capabilities,
                    ),
            )
        val (parsedBody, headers) = transport.sendWithHeaders(initReq)
        sessionId = headers["Mcp-Session-Id"] ?: (parsedBody as? Map<String, Any>)
            ?.get("result")
            ?.let { (it as? Map<String, Any>)?.get("sessionId") as? String }
        // sessionId remains null if neither header nor body provided it
    }

    fun listTools(): List<ToolSpecification> {
        val resp =
            transport.send(
                mapOf("jsonrpc" to "2.0", "method" to "tools/list", "id" to 2),
                sessionId,
            )
        return Converter.convert(mapper.readTree(resp))
    }

    fun callTool(request: CallToolRequest): String {
        val resp = transport.send(request.toRpc(3), sessionId)
        return resp.toString()
    }

    fun closeGracefully() {
        transport.send(
            mapOf("jsonrpc" to "2.0", "method" to "shutdown", "id" to 10),
            sessionId,
        )
    }
}

// Helper data classes for RPC

data class CreateMessageRequest(
    val content: String,
)

data class CreateMessageResult(
    val response: Any,
)

data class CallToolRequest(
    val name: String,
    val params: JsonNode,
) {
    fun toRpc(id: Int) =
        mapOf(
            "jsonrpc" to "2.0",
            "method" to "tools/call",
            "id" to id,
            "params" to mapOf("name" to name, "arguments" to params),
        )
}
