package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.data.annotation.Id
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MockFunSaveTool {
    private val mapper = jacksonObjectMapper()
    private val logger = KotlinLogging.logger { }

    companion object {
        const val TOOL_NAME = "mock_fun_save_tool"
    }

    fun loadTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "functionDefinition" to
                            mapOf(
                                "type" to "string",
                                "description" to "json string of the function definition to save.",
                            ),
                    ),
                "required" to listOf("functionDefinition"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = TOOL_NAME,
            description = "Saves function definition in the database.",
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
        logger.info { "functionDefinition to save: ${mapper.readTree(arguments)["functionDefinition"].asText()}" }
        return "functionId: ${UUID.randomUUID()}"
    }
}

//data class FunctionDefinition(
//    @Id
//    val id: String,
//    val
//)
