package com.masaic.openai.tool.mcp

import com.masaic.openai.tool.ToolDefinition
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.McpClient
import dev.langchain4j.mcp.client.transport.McpTransport
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class MCPToolExecutor() {
    private val log = LoggerFactory.getLogger(MCPToolExecutor::class.java)
    private val mcpClients = mutableMapOf<String, McpClient>()

    fun connectServer(serverName: String, mcpServer: MCPServer): McpClient {
        val mcpClient = when {
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

    fun executeTool(tool: ToolDefinition, arguments: String): String? {
        val mcpTool = tool as McpToolDefinition
        val mcpClient = mcpClients[mcpTool.serverInfo.id] ?: return null
        return mcpClient.executeTool(ToolExecutionRequest.builder().name(mcpTool.name).arguments(arguments).build())
    }

    fun shutdown() {
        mcpClients.forEach { (_, mcpClient) ->
            mcpClient.close()
        }
    }

    private fun connectOverHttp(serverName: String, mcpServer: MCPServer): McpClient {
        val transport: McpTransport = HttpMcpTransport.Builder()
            .sseUrl(mcpServer.url)
            .build()

        val mcpClient = DefaultMcpClient.Builder()
            .transport(transport)
            .build()
        log.info("MCP HTTP client connected for $serverName server at: ${mcpServer.url}")
        return mcpClient
    }

    private fun connectOverStdIO(serverName: String, mcpServer: MCPServer): McpClient {
        val command = buildList<String> {
            mcpServer.command?.let { add(it) }
            mcpServer.args.forEach { arg ->
                val envVar = mcpServer.env[arg]
                val envValue = envVar?.let { System.getenv(it) ?: it }
                add(envValue ?: arg)
            }
        }

        val transport: McpTransport = StdioMcpTransport.Builder()
            .command(command)
            .build()

        val mcpClient = DefaultMcpClient.Builder()
            .transport(transport)
            .build()

        log.info("MCP StdIO client connected for $serverName server with command: ${command.joinToString(" ")}")
        return mcpClient
    }
}

@Component
class MCPToolRegistry {
    private val log = LoggerFactory.getLogger(MCPToolRegistry::class.java)
    private val toolRepository = mutableMapOf<String, ToolDefinition>()

    fun registerMCPTools(serverName: String, mcpClient: McpClient) {
        registerMCPToolDefinitions(serverName, mcpClient)
    }

    private fun addTool(tool: ToolDefinition) {
        toolRepository[tool.name] = tool
    }

    fun findByName(name: String): ToolDefinition? {
        return toolRepository[name]
    }

    fun findAll(): List<ToolDefinition> {
        return toolRepository.values.toList()
    }

    fun cleanUp() {
        toolRepository.clear()
    }

    private fun registerMCPToolDefinitions(serverId: String, mcpClient: McpClient) {
        val toolSpecs = mcpClient.listTools()
        toolSpecs.forEach { toolSpec ->
            val tool = McpToolDefinition(
                name = toolSpec.name(),
                description = toolSpec.description(),
                parameters = toolSpec.parameters(),
                mcpServerInfo = MCPServerInfo(serverId)
            )
            log.info("Adding tool: $tool")
            addTool(tool)
        }
    }
}