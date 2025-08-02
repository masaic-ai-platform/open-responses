package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.api.model.MCPTool
import ai.masaic.openresponses.tool.mcp.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.errors.OpenAIException
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.responses.ResponseCreateParams
import dev.langchain4j.model.chat.request.json.*
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.http.codec.ServerSentEvent
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
    private val objectMapper: ObjectMapper,
    private val mcpClientFactory: McpClientFactory,
) {
    @Value("\${open-responses.tools.mcp.enabled:false}")
    private val toolsMCPEnabled: Boolean = false

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
        val toolName =
            if (tool.hosting == ToolHosting.REMOTE) {
                (tool as McpToolDefinition).serverInfo.unQualifiedToolName(tool.name)
            } else {
                tool.name
            }
        return ToolMetadata(
            id = tool.id,
            name = toolName,
            description = tool.description,
            protocol = tool.protocol,
            hosting = tool.hosting,
        )
    }

    /**
     * Builds an alias map from the provided tools.
     *
     * @param tools List of tools to extract aliases from
     * @return Map of alias names to actual tool names
     */
    fun buildAliasMap(tools: List<Any>): Map<String, String> {
        val aliasMap = mutableMapOf<String, String>()
        tools.forEach { tool ->
            val properties = objectMapper.convertValue(tool, Map::class.java)
            val type = properties["type"] as? String
            val alias = properties["alias"] as? String
            if (type != null && alias != null && type != alias) {
                aliasMap[alias] = type
            }
        }
        return aliasMap
    }

    /**
     * Resolves a tool name to its actual implementation name using the context.
     *
     * @param name The name to resolve (could be an alias)
     * @param context The tool request context
     * @return The resolved tool name
     */
    fun resolveToolName(
        name: String,
        context: ToolRequestContext,
    ): String = context.aliasMap[name] ?: name

    /**
     * Resolves a tool name to its actual implementation name using the completion context.
     *
     * @param name The name to resolve (could be an alias)
     * @param context The completion tool request context
     * @return The resolved tool name
     */
    fun resolveToolName(
        name: String,
        context: CompletionToolRequestContext,
    ): String = context.aliasMap[name] ?: name

    /**
     * Retrieves a tool as a FunctionTool by name.
     *
     * @param name Name of the tool to retrieve
     * @param context The tool request context
     * @return FunctionTool representation if found, null otherwise
     */
    fun getFunctionTool(
        name: String,
        context: ToolRequestContext,
    ): FunctionTool? {
        val resolvedName = resolveToolName(name, context)
        val toolDefinition = nativeToolRegistry.findByName(resolvedName) ?: mcpToolRegistry.findByName(resolvedName) ?: return null
        return when {
            toolDefinition is NativeToolDefinition -> NativeToolDefinition.toFunctionTool(toolDefinition)
            else -> (toolDefinition as McpToolDefinition).toFunctionTool()
        }
    }

    /**
     * Retrieves a tool as a FunctionTool by name using the Completion context.
     *
     * @param name Name of the tool to retrieve
     * @param context The completion tool request context
     * @return FunctionTool representation if found, null otherwise
     */
    fun getFunctionTool(
        name: String,
        context: CompletionToolRequestContext,
    ): FunctionTool? {
        val resolvedName = resolveToolName(name, context)
        val toolDefinition = nativeToolRegistry.findByName(resolvedName) ?: mcpToolRegistry.findByName(resolvedName) ?: return null
        return when {
            toolDefinition is NativeToolDefinition -> NativeToolDefinition.toFunctionTool(toolDefinition)
            else -> (toolDefinition as McpToolDefinition).toFunctionTool()
        }
    }

    /**
     * Retrieves a tool as a FunctionTool by name using the Completion context.
     *
     * @param name Name of the tool to retrieve
     * @return FunctionTool representation if found, null otherwise
     */
    fun getFunctionTool(
        name: String,
    ): FunctionTool? {
        val toolDefinition = nativeToolRegistry.findByName(name) ?: mcpToolRegistry.findByName(name) ?: return null
        return when {
            toolDefinition is NativeToolDefinition -> NativeToolDefinition.toFunctionTool(toolDefinition)
            else -> (toolDefinition as McpToolDefinition).toFunctionTool()
        }
    }

    fun getRemoteMcpTools(
        mcpTool: MCPTool,
    ): List<FunctionTool> {
        val remoteTools = getRemoteMcpToolDefinitions(mcpTool)
        return remoteTools.map { it.toFunctionTool() }
    }

    fun getRemoteMcpToolsForChatCompletion(
        mcpTool: MCPTool,
    ): List<ChatCompletionTool> {
        val remoteTools = getRemoteMcpToolDefinitions(mcpTool)
        return remoteTools.map { it.toChatCompletionTool(objectMapper) }
    }

    private fun getRemoteMcpToolDefinitions(
        mcpTool: MCPTool,
    ): List<McpToolDefinition> {
        val info = MCPServerInfo(mcpTool.serverLabel, mcpTool.serverUrl)
        val allowedTools = mcpTool.allowedTools.map { info.qualifiedToolName(it) }
        val mcpServerInfo = mcpToolRegistry.findServerById(info.serverIdentifier())
        return if (mcpServerInfo == null || mcpServerInfo.tools.isEmpty()) {
            val mcpClient = mcpClientFactory.init(mcpTool.serverLabel, mcpTool.serverUrl, mcpTool.headers)
            val availableTools = mcpClient.listTools(MCPServerInfo(mcpTool.serverLabel, mcpTool.serverUrl, mcpTool.headers))
            availableTools.forEach {
                mcpToolRegistry.addTool(it)
            }
            mcpToolRegistry.addMcpServer(MCPServerInfo(mcpTool.serverLabel, mcpTool.serverUrl, mcpTool.headers, availableTools.map { it.name }))
            mcpToolExecutor.addMcpClient(info.serverIdentifier(), mcpClient)

            if (allowedTools.isEmpty()) {
                availableTools
            } else {
                availableTools.filter { allowedTools.contains(it.name) }
            }
        } else {
            val tools = mutableListOf<McpToolDefinition>()
            mcpServerInfo.tools.forEach {
                if (allowedTools.isEmpty()) {
                    val toolDef = mcpToolRegistry.findByName(it) ?: throw IllegalStateException("Unable to find mcp tool $it in the registry")
                    tools.add((toolDef as McpToolDefinition))
                } else if (allowedTools.contains(it)) {
                    val toolDef = mcpToolRegistry.findByName(it) ?: throw IllegalStateException("Unable to find mcp tool $it in the registry")
                    tools.add((toolDef as McpToolDefinition))
                }
            }
            return tools
        }
    }

    /**
     * Retrieves a tool as a FunctionTool by name using the Completion context.
     *
     * @param name Name of the tool to retrieve
     * @return FunctionTool representation if found, null otherwise
     */
    fun getChatCompletionTool(
        name: String,
    ): ChatCompletionTool? {
        val toolDefinition = nativeToolRegistry.findByName(name) ?: mcpToolRegistry.findByName(name) ?: return null
        return when {
            toolDefinition is NativeToolDefinition -> NativeToolDefinition.toChatCompletionTool(toolDefinition, objectMapper)
            else -> (toolDefinition as McpToolDefinition).toChatCompletionTool(objectMapper)
        }
    }

    /**
     * Executes a tool with the provided arguments (Response Flow).
     */
    suspend fun executeTool(
        name: String,
        arguments: String,
        params: ResponseCreateParams,
        openAIClient: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: ToolRequestContext,
    ): String? {
        try {
            val resolvedName = resolveToolName(name, context)
            val tool = findToolByName(resolvedName) ?: return null

            // Create unified context and parameter accessor
            val unifiedContext = UnifiedToolContext(context.aliasMap)
            val paramsAccessor = ResponseParamsAdapter(params, objectMapper)

            // Call executeToolByProtocol without wrapper
            return executeToolByProtocol(tool, resolvedName, arguments, paramsAccessor, openAIClient, eventEmitter, toolMetadata + mapOf("originalName" to name), unifiedContext)
        } catch (e: Exception) {
            return handleToolExecutionError(name, arguments, e)
        }
    }

    /**
     * Executes a tool with the provided arguments for the Completion flow.
     */
    suspend fun executeTool(
        name: String,
        arguments: String,
        params: ChatCompletionCreateParams,
        openAIClient: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: CompletionToolRequestContext,
    ): String? {
        try {
            val resolvedName = resolveToolName(name, context)
            val tool = findToolByName(resolvedName) ?: return null

            // Create unified context and parameter accessor
            val unifiedContext = UnifiedToolContext(context.aliasMap)
            val paramsAccessor = ChatCompletionParamsAdapter(params, objectMapper)

            // Call executeToolByProtocol without wrapper
            return executeToolByProtocol(tool, resolvedName, arguments, paramsAccessor, openAIClient, eventEmitter, toolMetadata + mapOf("originalName" to name), unifiedContext)
        } catch (e: Exception) {
            return handleToolExecutionError(name, arguments, e)
        }
    }

    /**
     * Finds a tool by its name.
     */
    fun findToolByName(name: String): ToolDefinition? = nativeToolRegistry.findByName(name) ?: mcpToolRegistry.findByName(name)

    /**
     * Executes a tool based on its protocol, using unified context/params.
     *
     * @param tool The tool definition
     * @param resolvedName The resolved name of the tool
     * @param arguments The arguments for tool execution
     * @param paramsAccessor Unified accessor for tool configuration and basic params
     * @param openAIClient OpenAI client
     * @param eventEmitter Event emitter
     * @param toolMetadata Additional metadata
     * @param context Unified tool context
     * @return The result of tool execution
     */
    private suspend fun executeToolByProtocol(
        tool: ToolDefinition,
        resolvedName: String,
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        openAIClient: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String? {
        val toolResult =
            when (tool.protocol) {
                ToolProtocol.NATIVE -> nativeToolRegistry.executeTool(resolvedName, arguments, paramsAccessor, openAIClient, eventEmitter, toolMetadata, context)
                ToolProtocol.MCP -> mcpToolExecutor.executeTool(tool, arguments, paramsAccessor, openAIClient)
            }
        log.debug("tool ${toolMetadata["originalName"]} (resolved: $resolvedName) executed with arguments: $arguments gave result: $toolResult")
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
        if (e is OpenAIException || e is IllegalArgumentException) {
            throw e
        }
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
        if (!toolsMCPEnabled) {
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
     * Converts an MCP tool definition to a FunctionTool.
     *
     * @return FunctionTool representation of this MCP tool definition
     */
    private fun McpToolDefinition.toChatCompletionTool(objectMapper: ObjectMapper): ChatCompletionTool {
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
        return ChatCompletionTool
            .builder()
            .type(JsonValue.from("function"))
            .function(
                FunctionDefinition
                    .builder()
                    .name(this.name)
                    .description(this.description)
                    .parameters(
                        objectMapper.convertValue(parametersMap, FunctionParameters::class.java),
                    ).build(),
            ).build()
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
