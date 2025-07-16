package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import java.util.UUID

class MockSaveTool {
    private val mapper = jacksonObjectMapper()
    private val logger = KotlinLogging.logger { }

    companion object {
        const val TOOL_NAME = "mock_save_tool"
    }

    fun loadTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "mockFunctionId" to
                            mapOf(
                                "type" to "string",
                                "description" to "unique id of the function definition saved in the database.",
                            ),
                        "mockReqResDefinitions" to
                            mapOf(
                                "type" to "string",
                                "description" to "json string of the mock definitions to save into database.",
                            ),
                    ),
                "required" to listOf("mockFunctionId", "mockReqResDefinitions"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = TOOL_NAME,
            description = "Saves mock requests and responses for a function in the database",
            parameters = parameters,
        )
    }

    suspend fun executeTool(
        resolvedName: String,
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        client: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String? {
        // code to persist tool will be written
        logger.info { "mocks to save to save: ${mapper.readTree(arguments)["mockReqResDefinitions"].asText()}" }
        return "functionId: ${UUID.randomUUID()}"
    }
}
