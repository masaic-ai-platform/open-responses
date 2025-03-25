package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.tool.ToolDefinition
import ai.masaic.openresponses.tool.ToolHosting
import ai.masaic.openresponses.tool.ToolProtocol
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.Serializable
import java.util.*

/**
 * Represents configuration for an MCP server.
 *
 * @property command The command to execute for starting a self-hosted MCP server
 * @property args Command-line arguments for the MCP server
 * @property env Environment variables to set when starting the MCP server
 * @property url The URL of the MCP server if it's remotely hosted
 */
@Serializable
data class MCPServer(
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
)

/**
 * Configuration containing a mapping of server names to their MCP server configurations.
 *
 * @property mcpServers Map of server names to their corresponding MCPServer configurations
 */
@Serializable
data class MCPServers(
    val mcpServers: Map<String, MCPServer> = emptyMap(),
)

/**
 * Defines an MCP tool with its parameters and server information.
 *
 * Extends the base [ToolDefinition] class with MCP-specific properties.
 *
 * @property id Unique identifier for the tool
 * @property protocol Communication protocol used by the tool
 * @property hosting Hosting configuration for the tool
 * @property name Human-readable name of the tool
 * @property description Detailed description of what the tool does
 * @property parameters JSON schema defining the parameters accepted by the tool
 * @property serverInfo Information about the MCP server hosting this tool
 */
data class McpToolDefinition(
    override val id: String = UUID.randomUUID().toString(),
    override val protocol: ToolProtocol = ToolProtocol.MCP,
    override val hosting: ToolHosting,
    override val name: String,
    override val description: String,
    val parameters: JsonObjectSchema,
    val serverInfo: MCPServerInfo,
) : ToolDefinition(id, protocol, hosting, name, description) {
    /**
     * Secondary constructor with simplified parameter list.
     *
     * Creates a tool with MASAIC_MANAGED hosting and a random UUID.
     *
     * @param parameters JSON schema for the tool parameters
     * @param protocol Communication protocol (defaults to MCP)
     * @param name Human-readable name of the tool
     * @param description Detailed description of the tool
     * @param mcpServerInfo Information about the MCP server hosting this tool
     */
    constructor(
        parameters: JsonObjectSchema,
        protocol: ToolProtocol = ToolProtocol.MCP,
        name: String,
        description: String,
        mcpServerInfo: MCPServerInfo,
    ) : this(
        UUID.randomUUID().toString(),
        ToolProtocol.MCP,
        ToolHosting.MASAIC_MANAGED,
        name,
        description,
        parameters,
        mcpServerInfo,
    )

    override fun toString(): String = "McpTool(name='$name', description='$description', protocol=$protocol, parametersType=${parameters.javaClass.simpleName})"
}

/**
 * Information about an MCP server.
 *
 * @property id Unique identifier for the MCP server
 */
data class MCPServerInfo(
    val id: String,
)
