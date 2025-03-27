package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.tool.mcp.MCPServer
import ai.masaic.openresponses.tool.mcp.MCPServers
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import ai.masaic.openresponses.tool.mcp.MCPToolRegistry
import ai.masaic.openresponses.tool.mcp.McpToolDefinition
import dev.langchain4j.mcp.client.McpClient
import dev.langchain4j.model.chat.request.json.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.charset.Charset

/**
 * Service responsible for managing tool operations including loading, listing, and executing MCP tools.
 *
 * This service handles the lifecycle of tools, from loading them at application startup,
 * providing access to available tools, and executing tool operations.
 *
 * @property mcpToolRegistry Registry that manages tool definitions
 * @property mcpToolExecutor Executor that handles tool execution
 */
@Service
class ToolService(
    private val mcpToolRegistry: MCPToolRegistry,
    private val mcpToolExecutor: MCPToolExecutor,
    private val resourceLoader: ResourceLoader,
    private val nativeToolRegistry: NativeToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(ToolService::class.java)

    private companion object {
        const val DEFAULT_CONFIG_PATH = "classpath:mcp-servers-config.json"
        const val MCP_CONFIG_ENV_VAR = "MCP_SERVER_CONFIG_FILE_PATH"
    }

    /**
     * Lists all available tools.
     *
     * @return List of tool metadata representing all available tools
     */
    fun listAvailableTools(): List<ToolMetadata> {
        val availableTools =
            nativeToolRegistry
                .findAll()
                .map { tool ->
                    ToolMetadata(
                        id = tool.id,
                        name = tool.name,
                        description = tool.description,
                    )
                }.toMutableList()

        availableTools.addAll(
            mcpToolRegistry.findAll().map { tool ->
                ToolMetadata(
                    id = tool.id,
                    name = tool.name,
                    description = tool.description,
                )
            },
        )
        return availableTools
    }

    /**
     * Retrieves a specific tool by name.
     *
     * @param name Name of the tool to retrieve
     * @return Tool metadata if found, null otherwise
     */
    fun getAvailableTool(name: String): ToolMetadata? {
        val tool = nativeToolRegistry.findByName(name) ?: mcpToolRegistry.findByName(name) ?: return null
        return ToolMetadata(
            id = tool.id,
            name = tool.name,
            description = tool.description,
        )
    }

    /**
     * Retrieves a tool as a FunctionTool by name.
     *
     * @param name Name of the tool to retrieve
     * @return FunctionTool representation if found, null otherwise
     */
    fun getFunctionTool(name: String): FunctionTool? {
        val toolDefinition = nativeToolRegistry.findByName(name) ?: mcpToolRegistry.findByName(name) ?: return null
        return when {
            toolDefinition is NativeToolDefinition -> return NativeToolDefinition.toFunctionTool(toolDefinition)
            else -> (toolDefinition as McpToolDefinition).toFunctionTool()
        }
    }

    /**
     * Executes a tool with the provided arguments.
     *
     * @param name Name of the tool to execute
     * @param arguments JSON string containing the arguments for tool execution
     * @return Result of the tool execution as a string, or null if the tool isn't found
     */
    fun executeTool(
        name: String,
        arguments: String,
    ): String? {
        try {
            val tool = findToolByName(name) ?: return null
            return executeToolByProtocol(tool, name, arguments)
        } catch (e: Exception) {
            return handleToolExecutionError(name, arguments, e)
        }
    }

    /**
     * Finds a tool by its name.
     *
     * @param name The name of the tool to find
     * @return The tool definition if found, null otherwise
     */
    private fun findToolByName(name: String): ToolDefinition? = nativeToolRegistry.findByName(name) ?: mcpToolRegistry.findByName(name)

    /**
     * Executes a tool based on its protocol.
     *
     * @param tool The tool definition
     * @param name The name of the tool
     * @param arguments The arguments for tool execution
     * @return The result of tool execution
     */
    private fun executeToolByProtocol(
        tool: ToolDefinition,
        name: String,
        arguments: String,
    ): String? {
        val toolResult =
            when (tool.protocol) {
                ToolProtocol.NATIVE -> nativeToolRegistry.executeTool(name, arguments)
                ToolProtocol.MCP -> mcpToolExecutor.executeTool(tool, arguments)
            }
        log.debug("tool $name executed with arguments: $arguments gave result: $toolResult")
        return toolResult
    }

    /**
     * Handles errors that occur during tool execution.
     *
     * @param name The name of the tool
     * @param arguments The arguments that were provided
     * @param e The exception that occurred
     * @return An error message
     */
    private fun handleToolExecutionError(
        name: String,
        arguments: String,
        e: Exception,
    ): String {
        val errorMessage = "Tool $name execution with arguments $arguments failed with error message: ${e.message}"
        log.error(errorMessage, e)
        return errorMessage
    }

    /**
     * Initializes and loads all tools on application startup.
     *
     * Reads configuration from the file specified by MCP_SERVER_CONFIG_FILE_PATH
     * environment variable or uses the default path.
     */
    @PostConstruct
    fun loadTools() {
        if (!isMcpToolsEnabled()) {
            log.info("MCP tools are not enabled, skipping loading of MCP tools.")
            return
        }

        val configPath = determineConfigFilePath()
        val mcpServerConfigJson = loadConfigurationContent(configPath)

        if (mcpServerConfigJson.isEmpty()) {
            log.warn("MCP server config file is empty. No MCP tools will be loaded.")
            return
        }

        loadToolRegistry(mcpServerConfigJson)
    }

    /**
     * Checks if MCP tools are enabled via environment variable.
     *
     * @return true if MCP tools are enabled, false otherwise
     */
    private fun isMcpToolsEnabled(): Boolean = System.getenv("TOOLS_MCP_ENABLED")?.toBoolean() ?: true

    /**
     * Determines the configuration file path from environment variable or default.
     *
     * @return The resolved configuration file path
     */
    private fun determineConfigFilePath(): String {
        var filePath = System.getenv(MCP_CONFIG_ENV_VAR) ?: DEFAULT_CONFIG_PATH
        if (!filePath.startsWith("classpath:") && !filePath.startsWith("file:") && !filePath.startsWith("http")) {
            filePath = "file:$filePath"
        }
        return filePath
    }

    /**
     * Loads the configuration content from the specified path.
     *
     * @param configPath The path to load the configuration from
     * @return The configuration content as a string
     */
    private fun loadConfigurationContent(configPath: String): String =
        try {
            resourceLoader.getResource(configPath).getContentAsString(Charset.defaultCharset())
        } catch (e: Exception) {
            e.printStackTrace()
            log.warn("$MCP_CONFIG_ENV_VAR environment variable not set or file not found. No MCP tools will be loaded.")
            ""
        }

    /**
     * Cleans up resources when the application is shutting down.
     */
    @PreDestroy
    fun cleanup() {
        mcpToolRegistry.cleanUp()
        mcpToolExecutor.shutdown()
    }

    /**
     * Loads tool registry from the provided configuration JSON.
     *
     * @param mcpServerConfigJson JSON configuration string for MCP servers
     */
    private fun loadToolRegistry(mcpServerConfigJson: String) {
        val servers = json.decodeFromString<MCPServers>(mcpServerConfigJson)
        servers.mcpServers.forEach { (serverName, serverConfig) ->
            connectAndRegisterServer(serverName, serverConfig)
        }
    }

    /**
     * Connects to an MCP server and registers its tools.
     *
     * @param serverName The name of the server
     * @param serverConfig The server configuration
     */
    private fun connectAndRegisterServer(
        serverName: String,
        serverConfig: MCPServer,
    ) {
        try {
            val mcpClient = connectToMcpServer(serverName, serverConfig)
            registerMcpServerTools(serverName, mcpClient)
            log.info("Successfully loaded tools for MCP server: $serverName")
        } catch (e: Exception) {
            handleServerConnectionError(serverName, e)
        }
    }

    /**
     * Connects to an MCP server.
     *
     * @param serverName The name of the server
     * @param serverConfig The server configuration
     * @return The MCP client
     */
    private fun connectToMcpServer(
        serverName: String,
        serverConfig: MCPServer,
    ): McpClient = mcpToolExecutor.connectServer(serverName, serverConfig)

    /**
     * Registers tools from an MCP server.
     *
     * @param serverName The name of the server
     * @param mcpClient The MCP client
     */
    private fun registerMcpServerTools(
        serverName: String,
        mcpClient: McpClient,
    ) {
        mcpToolRegistry.registerMCPTools(serverName, mcpClient)
    }

    /**
     * Handles errors that occur when connecting to an MCP server.
     *
     * @param serverName The name of the server
     * @param e The exception that occurred
     */
    private fun handleServerConnectionError(
        serverName: String,
        e: Exception,
    ) {
        log.warn(
            "Failed to connect to MCP server '$serverName': ${e.message}. If this server is necessary then fix the MCP config or server and restart the application.",
            e,
        )
        // Continue with next server instead of aborting
    }

    /**
     * Converts an MCP tool definition to a FunctionTool.
     *
     * @return FunctionTool representation of this MCP tool definition
     */
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
            strict = false,
        )
    }

    /**
     * Maps a JsonSchemaElement to a map structure that can be used in a FunctionTool.
     *
     * @param schema The schema element to map
     * @return A map representing the schema element
     */
    private fun mapJsonSchemaToMap(schema: JsonSchemaElement): MutableMap<String, Any> {
        val result = mutableMapOf<String, Any>()

        when (schema) {
            is JsonObjectSchema -> mapObjectSchema(schema, result)
            is JsonArraySchema -> mapArraySchema(schema, result)
            is JsonStringSchema -> mapPrimitiveSchema(schema, result, "string")
            is JsonIntegerSchema -> mapPrimitiveSchema(schema, result, "integer")
            is JsonNumberSchema -> mapPrimitiveSchema(schema, result, "number")
            is JsonBooleanSchema -> mapPrimitiveSchema(schema, result, "boolean")
            else -> {
                // Default to string for unknown types
                result["type"] = "string"
            }
        }

        return result
    }

    /**
     * Maps an object schema to a map structure.
     *
     * @param schema The object schema to map
     * @param result The result map to populate
     */
    private fun mapObjectSchema(
        schema: JsonObjectSchema,
        result: MutableMap<String, Any>,
    ) {
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

    /**
     * Maps an array schema to a map structure.
     *
     * @param schema The array schema to map
     * @param result The result map to populate
     */
    private fun mapArraySchema(
        schema: JsonArraySchema,
        result: MutableMap<String, Any>,
    ) {
        result["type"] = "array"
        schema.description()?.let { result["description"] = it }
        schema.items()?.let { result["items"] = mapJsonSchemaToMap(it) }
    }

    /**
     * Maps a primitive schema to a map structure.
     *
     * @param schema The primitive schema to map
     * @param result The result map to populate
     * @param type The type of the primitive schema
     */
    private fun mapPrimitiveSchema(
        schema: JsonSchemaElement,
        result: MutableMap<String, Any>,
        type: String,
    ) {
        result["type"] = type
        when (schema) {
            is JsonStringSchema -> schema.description()?.let { result["description"] = it }
            is JsonIntegerSchema -> schema.description()?.let { result["description"] = it }
            is JsonNumberSchema -> schema.description()?.let { result["description"] = it }
            is JsonBooleanSchema -> schema.description()?.let { result["description"] = it }
        }
    }
}

@Component
class NativeToolRegistry {
    private val log = LoggerFactory.getLogger(NativeToolRegistry::class.java)
    private val toolRepository = mutableMapOf<String, ToolDefinition>()

    init {
        toolRepository["think"] = loadExtendedThinkTool()
    }

    fun findByName(name: String): ToolDefinition? = toolRepository[name]

    fun findAll(): List<ToolDefinition> = toolRepository.values.toList()

    fun executeTool(
        name: String,
        arguments: String,
    ): String? {
        val tool = toolRepository[name] ?: return null
        log.debug("Executing tool $name with arguments: $arguments")
        return "Your thought has been logged."
    }

    /**
     * Loads the extended "think" tool definition.
     *
     * This function creates a `NativeToolDefinition` for the "think" tool with predefined parameters.
     * The tool is designed to append a thought to the log without obtaining new information or changing the database.
     *
     * @return A `NativeToolDefinition` instance representing the "think" tool.
     */
    private fun loadExtendedThinkTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to mapOf("thought" to mapOf("type" to "string", "description" to "A thought to think about")),
                "required" to listOf("thought"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "think",
            description = "Use the tool to think about something. It will not obtain new information or change the database, but just append the thought to the log.",
            parameters = parameters,
        )
    }
}

/**
 * Data class representing metadata about a tool.
 *
 * @property id Unique identifier for the tool
 * @property name Human-readable name of the tool
 * @property description Detailed description of what the tool does
 */
data class ToolMetadata(
    val id: String,
    val name: String,
    val description: String,
)

/**
 * Data class representing metadata about AI models.
 *
 * @property models List of AI model information
 */
data class AIModelsMetadata(
    val models: List<AIModelInfo>,
)

/**
 * Data class representing metadata about an AI model.
 *
 * @property id Unique identifier for the model
 * @property name Human-readable name of the model
 * @property description Detailed description of what the model does
 * @property provider Name of the provider of the model
 */
data class AIModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val provider: String,
)
