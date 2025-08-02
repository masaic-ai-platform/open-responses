package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.*
import ai.masaic.openresponses.tool.mcp.MCPServerInfo
import ai.masaic.openresponses.tool.mcp.McpToolDefinition
import ai.masaic.openresponses.tool.mcp.nativeToolDefinition
import ai.masaic.platform.api.repository.MockFunctionRepository
import ai.masaic.platform.api.utils.JsonSchemaMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import mu.KotlinLogging
import org.springframework.data.annotation.Id
import org.springframework.http.codec.ServerSentEvent
import java.time.Instant
import java.util.UUID

class MockFunSaveTool(
    private val mockFunctionRepository: MockFunctionRepository,
) : PlatformNativeTool(PlatformToolsNames.MOCK_FUN_SAVE_TOOL) {
    private val logger = KotlinLogging.logger { }

    override fun provideToolDef(): NativeToolDefinition =
        nativeToolDefinition {
            name(toolName)
            description("Saves function definition in the database.")
            parameters {
                property(
                    name = "functionDefinition",
                    type = "string",
                    description = "JSON string of the function definition to save.",
                    required = true,
                )
                property(
                    name = "outputSchema",
                    type = "string",
                    description = "JSON string of the output schema object this function returns.",
                    required = true,
                )
                additionalProperties = false
            }
        }

    override suspend fun executeTool(
        resolvedName: String,
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        client: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String? {
        val jsonTree = mapper.readTree(arguments)
        val functionDefinition = jsonTree["functionDefinition"].asText()
        logger.info { "functionDefinition to save: $functionDefinition" }
        val functionBody: FunctionBody = mapper.readValue(functionDefinition)
        val savedDefinition = mockFunctionRepository.upsert(MockFunctionDefinition(functionBody = functionBody, outputSchem = jsonTree["outputSchema"].asText()))
        return "functionId: ${savedDefinition.id}"
    }
}

data class MockFunctionDefinition(
    @Id
    val id: String = UUID.randomUUID().toString(),
    val functionBody: FunctionBody,
    val outputSchem: String,
    val createdAt: Instant = Instant.now(),
) {
    fun toMcpToolDefinition(serverInfo: MCPServerInfo): McpToolDefinition {
        val schemaElement = JsonSchemaMapper.mapToJsonSchemaElement(functionBody.parameters) as JsonObjectSchema
        return McpToolDefinition(
            id = id,
            hosting = ToolHosting.REMOTE,
            name = serverInfo.qualifiedToolName(functionBody.name),
            description = functionBody.description,
            parameters = schemaElement,
            serverInfo = serverInfo,
        )
    }
}

data class FunctionBody(
    val name: String,
    val description: String,
    val parameters: MutableMap<String, Any> = mutableMapOf(),
    val strict: Boolean = true,
)
