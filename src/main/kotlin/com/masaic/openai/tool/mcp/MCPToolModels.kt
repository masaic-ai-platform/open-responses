package com.masaic.openai.tool.mcp

import com.masaic.openai.tool.ToolDefinition
import com.masaic.openai.tool.ToolHosting
import com.masaic.openai.tool.ToolProtocol
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class MCPServer(
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null
)

@Serializable
data class MCPServers(
    val mcpServers: Map<String, MCPServer> = emptyMap()
)

data class McpToolDefinition(
    override val id: String = UUID.randomUUID().toString(),
    override val protocol: ToolProtocol = ToolProtocol.MCP,
    override val hosting: ToolHosting,
    override val name: String,
    override val description: String,
    val parameters: JsonObjectSchema,
    val serverInfo: MCPServerInfo
) : ToolDefinition(id, protocol, hosting, name, description) {
    constructor(
        parameters: JsonObjectSchema,
        protocol: ToolProtocol = ToolProtocol.MCP,
        name: String,
        description: String,
        mcpServerInfo: MCPServerInfo
    ) : this(
        UUID.randomUUID().toString(),
        ToolProtocol.MCP,
        ToolHosting.MASAIC_MANAGED,
        name,
        description,
        parameters,
        mcpServerInfo
    )

    override fun toString(): String {
        return "McpTool(name='$name', description='$description', protocol=$protocol, parametersType=${parameters.javaClass.simpleName})"
    }
}

data class MCPServerInfo(val id: String)