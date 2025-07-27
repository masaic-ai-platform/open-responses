package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.api.service.ResponseTimeoutException
import ai.masaic.openresponses.tool.ToolDefinition
import ai.masaic.openresponses.tool.ToolHosting
import ai.masaic.openresponses.tool.ToolParamsAccessor
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.Converter
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import kotlinx.coroutines.*
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
import kotlin.time.Duration.Companion.seconds

interface McpClientFactory {
    fun init(
        serverName: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): McpClient

    fun init(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient
}

open class SimpleMcpClientFactory : McpClientFactory {
    override fun init(
        serverName: String,
        url: String,
        headers: Map<String, String>,
    ): McpClient = SimpleMcpClient().init(serverName, url, headers)

    override fun init(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient = SimpleMcpClient().init(serverName, mcpServer)
}

interface McpClient {
    fun listTools(mcpServerInfo: MCPServerInfo): List<McpToolDefinition>

    fun executeTool(
        tool: ToolDefinition,
        arguments: String,
        paramsAccessor: ToolParamsAccessor?,
        openAIClient: OpenAIClient?,
        headers: Map<String, String>,
    ): String?

    fun close()
}

class SimpleMcpClient : McpClient {
    private val log = KotlinLogging.logger {}
    private var customClient: McpSyncClient? = null
    private var defaultMcpClient: DefaultMcpClient? = null
    private val mapper = jacksonObjectMapper()

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 60L
    }

    fun init(
        serverName: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): McpClient {
        val transport = HttpSseTransport(url)
        customClient =
            McpSyncClient
                .sync(transport)
                .requestTimeout(Duration.ofSeconds(60 * 1000))
                .build()
        runBlocking {
            customClient?.initialize(url, headers) ?: throw IllegalStateException("mcp client not initialised for $url")
        }
        log.info("MCP HTTP client connected for $serverName server at: $url")
        return this
    }

    fun init(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient {
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
                throw ResponseTimeoutException("Timed out while connecting to MCP server $serverName")
            } finally {
                executor.shutdownNow()
            }

        log.info("MCP StdIO client connected for $serverName server with command: ${command.joinToString(" ")}")
        return this
    }

    override fun listTools(mcpServerInfo: MCPServerInfo): List<McpToolDefinition> =
        defaultMcpClient?.listTools()?.map {
            val tool =
                McpToolDefinition(
                    name = it.name(),
                    description = it.description() ?: it.name(),
                    parameters = it.parameters(),
                    mcpServerInfo = mcpServerInfo,
                )
            log.info("Adding stdio mcp tool: $tool")
            tool
        } ?: customClient?.listTools(mcpServerInfo.headers)?.map {
            val tool =
                McpToolDefinition(
                    name = mcpServerInfo.qualifiedToolName(it.name()),
                    description = it.description() ?: it.name(),
                    parameters = it.parameters(),
                    mcpServerInfo = mcpServerInfo,
                    hosting = ToolHosting.REMOTE,
                )
            log.info("Adding remote mcp tool: $tool")
            tool
        }
            ?: emptyList()

    override fun executeTool(
        tool: ToolDefinition,
        arguments: String,
        paramsAccessor: ToolParamsAccessor?,
        openAIClient: OpenAIClient?,
        headers: Map<String, String>,
    ): String? {
        return customClient?.callTool(CallToolRequest(tool.name, mapper.readTree(arguments)), headers)
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

    override fun close() {
        defaultMcpClient?.close()
    }
}

/**
 * Low-level HTTP+SSE transport for MCP messages, with support for capturing session headers.
 */
class HttpSseTransport(
    private val endpoint: String,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build(),
) {
    private val mapper = jacksonObjectMapper()
    private val jsonMediaType = "application/json".toMediaType()
    private val log = KotlinLogging.logger {}
    private var sseEndpoint: String? = null
    private var messageEndpoint: String? = null

    init {
        if (endpoint.endsWith("sse")) {
            sseEndpoint = endpoint
        }
    }

    /**
     * Sends a JSON-RPC message via HTTP POST and returns the parsed body and response headers.
     */
    suspend fun init(
        headers: Map<String, String>,
    ): Pair<Any?, Headers> {
        if (sseEndpoint != null) {
            val messageEndpointDeferred = CompletableDeferred<String>()
            openSse(sseEndpoint = sseEndpoint!!, onMessage = { message ->
                // rawEvent is already deserialized as Any -> re-serialize to JSON text
                if (message.startsWith("/message")) {
                    val endpoint = sseEndpoint!!.replace("/sse", message)
                    messageEndpoint = endpoint
                    messageEndpointDeferred.complete(endpoint)
                }
            })
            
            // Wait for the messageEndpoint to be set before proceeding
            try {
                withTimeout(30.seconds) {
                    messageEndpointDeferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                throw ResponseTimeoutException("Timed out waiting for message endpoint from SSE")
            }
        }

        val url = messageEndpoint ?: endpoint
        val json = mapper.writeValueAsString(initPayload())
        val request =
            Request
                .Builder()
                .url(url)
                .post(json.toRequestBody(jsonMediaType))
                .header("Accept", "application/json, text/event-stream")
                .apply { 
                    // Add headers if not empty
                    if (headers.isNotEmpty()) {
                        headers.forEach { (key, value) -> header(key, value) }
                    }
                }.build()

        client.newCall(request).execute().use { resp ->
            val headers = resp.headers
            val code = resp.code
            val ct = resp.header("Content-Type").orEmpty()

            // Pure notifications/responses
            if (code == 202 && resp.body?.contentLength() == 0L) {
                return Pair(null, headers)
            }

            if (code in 400..503) { // TODO: doing minimal handling now.
                throw Exception("mcp server request failed with code=$code and response returned is: ${resp.body!!.string()}")
            }

            // Regular JSON body
            val raw = resp.body?.string().orEmpty()
            return Pair(raw, headers)
        }
    }

    private fun openSse(
        sseEndpoint: String,
        onMessage: (String) -> Unit,
    ) {
        val request =
            Request
                .Builder()
                .url(sseEndpoint)
                .get()
                .header("Accept", "text/event-stream")
                .build()

        client.newCall(request).execute().use { resp ->
            val code = resp.code
            if (code in 400..503) {
                throw Exception("mcp server request failed with code=$code and response returned is: ${resp.body!!.string()}")
            }

            val ct = resp.header("Content-Type").orEmpty()
            if (ct.startsWith("text/event-stream")) {
                val factory = EventSources.createFactory(client)
                factory.newEventSource(
                    request,
                    object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            log.debug { "received message= $data" }
                            data.let(onMessage)
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            log.error { "error message received from server: code=${response?.code}, message=${response?.body?.string()}" }
                            eventSource.cancel()
                        }

                        override fun onClosed(eventSource: EventSource) {
                            super.onClosed(eventSource)
                        }
                    },
                )
            }
        }
    }

    /**
     * Sends a JSON-RPC request, optionally streaming SSE events via onEvent.
     */
    fun send(
        payload: Any,
        sessionId: String? = null,
        onEvent: ((Any) -> Unit)? = null,
        headers: Map<String, String>,
        retryCount: Int = 0,
    ): String? {
        val url = messageEndpoint ?: endpoint
        val json = mapper.writeValueAsString(payload)
        val request =
            Request
                .Builder()
                .url(url)
                .post(json.toRequestBody(jsonMediaType))
                .header("Accept", "application/json,text/event-stream")
                .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
                .apply {
                    if (headers.isNotEmpty()) {
                        headers.forEach { (key, value) -> header(key, value) }
                    }
                }.build()

        client.newCall(request).execute().use { resp ->
            val code = resp.code
            val ct = resp.header("Content-Type").orEmpty()

            if (code == 202 && resp.body?.contentLength() == 0L) return null

            if (code == 400 && sseEndpoint != null) {
                if (retryCount < 4) {
                    log.warn { "MCP server returned 400 with response: ${resp.body!!.string()}, attempting to reconnect. Retry attempt: ${retryCount + 1}/4" }
                    runBlocking { init(headers) }
                    return send(payload, sessionId, onEvent, headers, retryCount + 1)
                } else {
                    log.error { "Failed to reconnect after 4 attempts. Giving up." }
                    throw Exception("mcp server request failed with code=$code after 4 retry attempts")
                }
            }

            if (code in 400..503) { // TODO: doing minimal handling now.
                throw Exception("mcp server request failed with code=$code and response returned is: ${resp.body!!.string()}")
            }

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
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    log.debug { "received message= $data" }
                    mapper.readValue<Any>(data)?.let(onMessage)
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    log.error { "error message received from server: code=${response?.code}, message=${response?.body?.string()}" }
                    eventSource.cancel()
                }

                override fun onClosed(eventSource: EventSource) {
                    super.onClosed(eventSource)
                }
            },
        )
    }

    private fun initPayload() =
        mapOf(
            "jsonrpc" to "2.0",
            "method" to "initialize",
            "id" to 1,
            "params" to
                mapOf(
                    "protocolVersion" to "2025-03-26",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to
                        mapOf(
                            "name" to "open-responses",
                            "version" to "1.0.0",
                        ),
                ),
        )
}

/**
 * Supported client capabilities.
 */
data class ClientCapabilities(
    val roots: Map<String, Any>,
)

/**
 * Synchronous MCP client.
 */
class McpSyncClient private constructor(
    private val transport: HttpSseTransport,
    private val capabilities: ClientCapabilities,
) {
    private var sessionId: String? = null
    private var listenSSE: Boolean = false
    private val mapper = jacksonObjectMapper()
    private val log = KotlinLogging.logger {}

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
        private var capabilities = ClientCapabilities(roots = mapOf("listChanged" to true))

        fun requestTimeout(d: Duration) = apply { timeout = d }

        fun capabilities(cap: ClientCapabilities) = apply { capabilities = cap }

        fun build() = McpSyncClient(transport, capabilities)
    }

    /**
     * Initializes the MCP session. If the server returns an Mcp-Session-Id header,
     * that value is used. Otherwise, no session ID is stored, and subsequent calls
     * are made without a session header.
     */
    suspend fun initialize(
        url: String,
        reqHeaders: Map<String, String>,
    ) {
        val (parsedBody, headers) = transport.init(headers = reqHeaders)
        sessionId = headers["Mcp-Session-Id"] ?: (parsedBody as? Map<String, Any>)
            ?.get("result")
            ?.let { (it as? Map<String, Any>)?.get("sessionId") as? String }

        log.info { "Connection with server established at $url with sessionId: $sessionId" }
        listenSSE = headers["Content-Type"] == "text/event-stream"
        log.info { "Server will send content as ${headers["Content-Type"]}, setting listenSSE=$listenSSE" }
    }

    fun listTools(headers: Map<String, String>): List<ToolSpecification> {
        if (listenSSE) {
            return listenListTools(headers)
        }
        val resp =
            transport.send(
                payload = mapOf("jsonrpc" to "2.0", "method" to "tools/list", "id" to 2),
                sessionId = sessionId,
                headers = headers,
            )
        return Converter.convert(mapper.readTree(resp as String))
    }

    private fun listenListTools(headers: Map<String, String>): List<ToolSpecification> {
        // we’ll wait for exactly one response and then return
        val latch = CountDownLatch(1)
        val collected = mutableListOf<ToolSpecification>()

        // fire off the RPC, streaming SSE events into our lambda
        transport.send(
            payload = mapOf("jsonrpc" to "2.0", "method" to "tools/list", "id" to 2),
            sessionId = sessionId,
            onEvent = { rawEvent ->
                // rawEvent is already deserialized as Any -> re-serialize to JSON text
                val text = mapper.writeValueAsString(rawEvent)
                val node = mapper.readTree(text)
                collected += Converter.convert(node)
                latch.countDown()
            },
            headers = headers,
        )

        // wait (with a timeout!) for the callback to fire
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw ResponseTimeoutException("Timed out waiting for tools/list response")
        }
        return collected
    }

    fun callTool(
        request: CallToolRequest,
        headers: Map<String, String>,
    ): String {
        if (listenSSE) {
            return listenCallTool(request, headers)
        }

        val resp = transport.send(payload = request.toRpc(3), sessionId = sessionId, headers = headers)
        return resp.toString()
    }

    private fun listenCallTool(
        request: CallToolRequest,
        headers: Map<String, String>,
    ): String {
        val latch = CountDownLatch(1)
        var toolResponse = "no_response_from_tool"
        transport.send(
            payload = request.toRpc(3),
            sessionId = sessionId,
            onEvent = { rawEvent ->
                // rawEvent is already deserialized as Any -> re-serialize to JSON text
                toolResponse = rawEvent.toString()
                latch.countDown()
            },
            headers = headers,
        )
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw ResponseTimeoutException("Timed out waiting for tools/call response")
        }
        return toolResponse
    }

    fun closeGracefully(headers: Map<String, String>) {
        transport.send(
            payload = mapOf("jsonrpc" to "2.0", "method" to "shutdown", "id" to 10),
            sessionId = sessionId,
            headers = headers,
        )
    }
}

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
