package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.tool.ToolDefinition
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.McpClient
import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.*

/**
 * Component responsible for executing MCP tools.
 *
 * This component manages connections to MCP servers and provides functionality
 * to execute tools on these servers.
 */
@Component
class MCPToolExecutor {
    private val log = LoggerFactory.getLogger(MCPToolExecutor::class.java)
    private val mcpClients = mutableMapOf<String, McpClient>()

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 20L
    }

    /**
     * Connects to an MCP server based on the provided configuration.
     *
     * @param serverName Name of the server to connect to
     * @param mcpServer Server configuration
     * @return McpClient instance connected to the server
     */
    fun connectServer(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient {
        val mcpClient =
            when {
                mcpServer.url != null && mcpServer.command == null -> {
                    connectOverHttp(serverName, mcpServer)
                }
                else -> {
                    connectOverStdIO(serverName, mcpServer)
                }
            }
        mcpClients[serverName] = mcpClient
        return mcpClient
    }

    /**
     * Executes a tool with the provided arguments.
     *
     * @param tool The tool definition to execute
     * @param arguments JSON string containing arguments for the tool
     * @return Result of the tool execution as a string, or null if the tool can't be executed
     */
    fun executeTool(
        tool: ToolDefinition,
        arguments: String,
    ): String? {
        val mcpTool = tool as McpToolDefinition
        val mcpClient = mcpClients[mcpTool.serverInfo.id] ?: return null
        return mcpClient.executeTool(
            ToolExecutionRequest
                .builder()
                .name(mcpTool.name)
                .arguments(arguments)
                .build(),
        )
    }

    /**
     * Shuts down all MCP clients, releasing resources.
     */
    fun shutdown() {
        mcpClients.forEach { (_, mcpClient) ->
            mcpClient.close()
        }
    }

    /**
     * Connects to an MCP server over HTTP.
     *
     * @param serverName Name of the server to connect to
     * @param mcpServer Server configuration
     * @return McpClient instance connected over HTTP
     */
    private fun connectOverHttp(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient {
        val transport: McpTransport =
            HttpMcpTransport
                .Builder()
                .sseUrl(mcpServer.url)
                .build()

        val mcpClient =
            DefaultMcpClient
                .Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.of(3, ChronoUnit.MINUTES))
                .build()
        log.info("MCP HTTP client connected for $serverName server at: ${mcpServer.url}")
        return mcpClient
    }

    /**
     * Connects to an MCP server over standard I/O.
     *
     * @param serverName Name of the server to connect to
     * @param mcpServer Server configuration
     * @return McpClient instance connected over standard I/O
     * @throws IllegalStateException if connection times out
     */
    private fun connectOverStdIO(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient {
        val command = buildCommand(mcpServer)
        log.info("Command to start server will be: ${command.joinToString(" ")}")

        // Create an executor with a single thread dedicated to this blocking operation
        val executor = Executors.newSingleThreadExecutor()

        val mcpClient =
            try {
                val future: Future<McpClient> =
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
                            DefaultMcpClient
                                .Builder()
                                .transport(transport)
                                .build()
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
        return mcpClient
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
}

/**
 * Component responsible for managing MCP tool definitions.
 *
 * This registry maintains a collection of tool definitions and provides
 * methods to register, find, and clean up tools.
 */
@Component
class MCPToolRegistry {
    private val log = LoggerFactory.getLogger(MCPToolRegistry::class.java)
    private val toolRepository = mutableMapOf<String, ToolDefinition>()

    /**
     * Registers MCP tools from the given client.
     *
     * @param serverName Name of the server hosting the tools
     * @param mcpClient Client connected to the server
     */
    fun registerMCPTools(
        serverName: String,
        mcpClient: McpClient,
    ) {
        registerMCPToolDefinitions(serverName, mcpClient)
    }

    /**
     * Adds a tool to the registry.
     *
     * @param tool Tool definition to add
     */
    private fun addTool(tool: ToolDefinition) {
        toolRepository[tool.name] = tool
    }

    /**
     * Finds a tool by name.
     *
     * @param name Name of the tool to find
     * @return Tool definition if found, null otherwise
     */
    fun findByName(name: String): ToolDefinition? = toolRepository[name]

    /**
     * Returns all registered tools.
     *
     * @return List of all tool definitions
     */
    fun findAll(): List<ToolDefinition> = toolRepository.values.toList()

    /**
     * Clears the tool repository.
     */
    fun cleanUp() {
        toolRepository.clear()
    }

    /**
     * Registers MCP tool definitions from the given client.
     *
     * @param serverId ID of the server hosting the tools
     * @param mcpClient Client connected to the server
     */
    private fun registerMCPToolDefinitions(
        serverId: String,
        mcpClient: McpClient,
    ) {
        val toolSpecs = mcpClient.listTools()
        toolSpecs.forEach { toolSpec ->
            val tool =
                McpToolDefinition(
                    name = toolSpec.name(),
                    description = toolSpec.description(),
                    parameters = toolSpec.parameters(),
                    mcpServerInfo = MCPServerInfo(serverId),
                )
            log.info("Adding tool: $tool")
            addTool(tool)
        }
    }
}
