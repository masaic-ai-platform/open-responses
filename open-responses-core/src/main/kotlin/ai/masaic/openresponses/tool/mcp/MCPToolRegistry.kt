package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.tool.ToolDefinition
import ai.masaic.openresponses.tool.ToolHosting
import ai.masaic.openresponses.tool.ToolParamsAccessor
import com.openai.client.OpenAIClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Component responsible for executing MCP tools.
 *
 * This component manages connections to MCP servers and provides functionality
 * to execute tools on these servers.
 */
@Component
class MCPToolExecutor(
    private val mcpClientFactory: McpClientFactory,
) {
    private val log = LoggerFactory.getLogger(MCPToolExecutor::class.java)
    private val mcpClients = mutableMapOf<String, McpClient>()

    /**
     * Connects to an MCP server based on the provided configuration.
     *
     * @param serverName Name of the server to connect to
     * @param mcpServer Server configuration
     * @return McpClient instance connected to the server
     */
    suspend fun connectServer(
        serverName: String,
        mcpServer: MCPServer,
    ): McpClient {
        val mcpClient = mcpClientFactory.init(serverName, mcpServer)
        mcpClients[serverName] = mcpClient
        return mcpClient
    }

    fun addMcpClient(
        serverName: String,
        mcpClient: McpClient,
    ) {
        mcpClients[serverName] = mcpClient
    }

    /**
     * Executes a tool with the provided arguments.
     *
     * @param tool The tool definition to execute
     * @param arguments JSON string containing arguments for the tool
     * @return Result of the tool execution as a string, or null if the tool can't be executed
     */
    suspend fun executeTool(
        tool: ToolDefinition,
        arguments: String,
        paramsAccessor: ToolParamsAccessor?,
        openAIClient: OpenAIClient?,
    ): String? {
        val mcpTool = tool as McpToolDefinition
        var serverId = mcpTool.serverInfo.id
        var toolName = mcpTool.name
        if (mcpTool.hosting == ToolHosting.REMOTE) {
            serverId = mcpTool.serverInfo.serverIdentifier()
            toolName = mcpTool.serverInfo.unQualifiedToolName(mcpTool.name)
        }

        val mcpClient = mcpClients[serverId] ?: return null
        return mcpClient.executeTool(tool.copy(name = toolName), arguments, paramsAccessor, openAIClient, headers = mcpTool.serverInfo.headers)
    }

    /**
     * Shuts down all MCP clients, releasing resources.
     */
    suspend fun shutdown() {
        mcpClients.forEach { (_, mcpClient) ->
            mcpClient.close()
        }
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
    private val serverRepository = mutableMapOf<String, MCPServerInfo>()

    /**
     * Registers MCP tools from the given client.
     *
     * @param serverName Name of the server hosting the tools
     * @param mcpClient Client connected to the server
     */
    suspend fun registerMCPTools(
        serverName: String,
        mcpClient: McpClient,
    ) {
        val mcpTools = mcpClient.listTools(MCPServerInfo(serverName))
        mcpTools.forEach { addTool(it) }
    }

    /**
     * Adds a tool to the registry.
     *
     * @param tool Tool definition to add
     */
    fun addTool(tool: ToolDefinition) {
        toolRepository[tool.name] = tool
    }

    fun addMcpServer(mcpServerInfo: MCPServerInfo) {
        serverRepository[mcpServerInfo.serverIdentifier()] = mcpServerInfo
    }

    /**
     * Finds a tool by name.
     *
     * @param name Name of the tool to find
     * @return Tool definition if found, null otherwise
     */
    fun findByName(name: String): ToolDefinition? = toolRepository[name]

    fun findServerById(id: String): MCPServerInfo? = serverRepository[id]

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
}
