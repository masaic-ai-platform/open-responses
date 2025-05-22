package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.tool.ToolDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
                    McpClient().init(serverName, mcpServer)
                }
                else -> {
                    McpClient().init(serverName, mcpServer, "stdio")
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
        return mcpClient.executeTool(tool, arguments)
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
        val mcpTools = mcpClient.listTools(serverName)
        mcpTools.forEach { addTool(it) }
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

//    /**
//     * Registers MCP tool definitions from the given client.
//     *
//     * @param serverId ID of the server hosting the tools
//     * @param mcpClient Client connected to the server
//     */
//    private fun registerMCPToolDefinitions(
//        serverId: String,
//        mcpClient: McpClient,
//    ) {
//        val toolSpecs = mcpClient.listTools()
//        toolSpecs.forEach { toolSpec ->
//            val tool =
//                McpToolDefinition(
//                    name = toolSpec.name(),
//                    description = toolSpec.description() ?: toolSpec.name(),
//                    parameters = toolSpec.parameters(),
//                    mcpServerInfo = MCPServerInfo(serverId),
//                )
//            log.info("Adding tool: $tool")
//            addTool(tool)
//        }
//    }
}
