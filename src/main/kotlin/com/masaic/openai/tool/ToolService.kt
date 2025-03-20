package com.masaic.openai.tool

import com.masaic.openai.api.model.FunctionTool
import com.masaic.openai.tool.mcp.MCPServers
import com.masaic.openai.tool.mcp.MCPToolExecutor
import com.masaic.openai.tool.mcp.MCPToolRegistry
import com.masaic.openai.tool.mcp.McpToolDefinition
import dev.langchain4j.model.chat.request.json.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

/**
 * Currently responsible for loading, listing, and executing only MCP tools.
 */
@Service
class ToolService(private val mcpToolRegistry: MCPToolRegistry, private val mcpToolExecutor: MCPToolExecutor) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(ToolService::class.java)

    fun listAvailableTools(): List<ToolMetadata> {
        return mcpToolRegistry.findAll().map { tool ->
            ToolMetadata(
                id = tool.id,
                name = tool.name,
                description = tool.description
            )
        }
    }

    fun getAvailableTool(name: String): ToolMetadata? {
        val tool = mcpToolRegistry.findByName(name) ?: return null
        return ToolMetadata(
            id = tool.id,
            name = tool.name,
            description = tool.description
        )
    }

    fun getFunctionTool(name: String): FunctionTool? {
        val toolDefinition = mcpToolRegistry.findByName(name) ?: return null
        return (toolDefinition as McpToolDefinition).toFunctionTool()
    }

    fun executeTool(name: String, arguments: String): String? {
        val tool = mcpToolRegistry.findByName(name) ?: return null
        return mcpToolExecutor.executeTool(tool, arguments)
    }

    @PostConstruct
    fun loadTools() {
        val filePath = System.getenv("MCP_SERVER_CONFIG_FILE_PATH") ?: "src/main/resources/mcp-servers-config.json"
        val mcpServerConfigJson = try {
            File(filePath).readText()
        } catch (e: Exception) {
            log.warn("MCP_SERVER_CONFIG_FILE_PATH environment variable not set. No MCP tools will be loaded.")
            return
        }

        if (mcpServerConfigJson.isEmpty()) {
            log.warn("MCP server config file is empty. No MCP tools will be loaded.")
        }

        loadToolRegistry(mcpServerConfigJson)
        return
    }

    @PreDestroy
    fun cleanup() {
        mcpToolRegistry.cleanUp()
        mcpToolExecutor.shutdown()
    }

    private fun loadToolRegistry(mcpServerConfigJson: String) {
        val servers = json.decodeFromString(MCPServers.serializer(), mcpServerConfigJson)
        servers.mcpServers.forEach { (name, server) ->
            try {
                val mcpClient = mcpToolExecutor.connectServer(name, server)
                mcpToolRegistry.registerMCPTools(name, mcpClient)
                log.info("Successfully loaded tools for MCP server: $name")
            } catch (e: Exception) {
                log.warn(
                    "Failed to connect to MCP server '$name': ${e.message}. If this server is necessary then fix the MCP config or server and restart the application.",
                    e
                )
                // Continue with next server instead of aborting
            }
        }
    }

    private fun McpToolDefinition.toFunctionTool(): FunctionTool {
        // Convert JsonObjectSchema to MutableMap<String, Any>
        val parametersMap = mutableMapOf<String, Any>()

        // Add type and required properties
        parametersMap["type"] = "object"

        // Add properties
        val propertiesMap = mutableMapOf<String, Any>()
        this.parameters.properties().forEach { (name, schema) ->
            propertiesMap[name] = mapJsonSchemaToMap(schema)
        }

        parametersMap["properties"] = propertiesMap

        // Add required fields if present
        this.parameters.required()?.let {
            if (it.isNotEmpty()) {
                parametersMap["required"] = it
            }
        }

        // Create and return the FunctionTool
        return FunctionTool(
            name = this.name,
            description = this.description,
            parameters = parametersMap,
            strict = false
        )
    }

    private fun mapJsonSchemaToMap(schema: JsonSchemaElement): MutableMap<String, Any> {
        val result = mutableMapOf<String, Any>()

        when (schema) {
            is JsonObjectSchema -> {
                result["type"] = "object"
                schema.description()?.let { result["description"] = it }

                val properties = mutableMapOf<String, Any>()
                schema.properties().forEach { (name, prop) ->
                    properties[name] = mapJsonSchemaToMap(prop)
                }
                if (properties.isNotEmpty()) {
                    result["properties"] = properties
                }

                schema.required()?.let {
                    if (it.isNotEmpty()) {
                        result["required"] = it
                    }
                }

                schema.additionalProperties()?.let {
                    result["additionalProperties"] = it
                }
            }

            is JsonArraySchema -> {
                result["type"] = "array"
                schema.description()?.let { result["description"] = it }
                schema.items()?.let { result["items"] = mapJsonSchemaToMap(it) }
            }

            is JsonStringSchema -> {
                result["type"] = "string"
                schema.description()?.let { result["description"] = it }
            }

            is JsonIntegerSchema -> {
                result["type"] = "integer"
                schema.description()?.let { result["description"] = it }
            }

            is JsonNumberSchema -> {
                result["type"] = "number"
                schema.description()?.let { result["description"] = it }
            }

            is JsonBooleanSchema -> {
                result["type"] = "boolean"
                schema.description()?.let { result["description"] = it }
            }

            else -> {
                // Default to string for unknown types
                result["type"] = "string"
            }
        }

        return result
    }
}

data class ToolMetadata(val id: String, val name: String, val description: String)