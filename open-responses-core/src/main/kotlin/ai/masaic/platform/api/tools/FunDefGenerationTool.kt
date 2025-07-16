package ai.masaic.platform.api.tools

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.platform.api.model.FunctionGenerationRequest
import ai.masaic.platform.api.model.FunctionGenerationResponse
import ai.masaic.platform.api.model.SystemSettings
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.tools.FunReqGatheringTool.Companion.TOOL_NAME
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component

@Profile("platform")
@Component
class FunDefGenerationTool(
    @Lazy private val modelService: ModelService,
    private val systemSettings: SystemSettings,
) {
    private val mapper = jacksonObjectMapper()
    private val logger = KotlinLogging.logger { }

    companion object {
        const val TOOL_NAME = "fun_def_generation_tool"
    }

    fun loadTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "functionDetails" to
                            mapOf(
                                "type" to "string",
                                "description" to "String parameter that accepts details details of the function to be generated. Can accept raw json Strings.",
                            ),
                        "context" to
                            mapOf(
                                "type" to "string",
                                "description" to "crisp and clear summary of the progress made so far in function definition generation.",
                            ),
                    ),
                "required" to listOf("functionDetails", "context"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = TOOL_NAME,
            description = "Generate the definition of the function based upon the details provided.",
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
        val jsonTree = mapper.readTree(arguments)
        val contextDetails: String = jsonTree["context"]?.asText() ?: ""
        val functionDetails: String = jsonTree["functionDetails"]?.asText() ?: throw IllegalStateException("function details for definition generation are necessary.")
        return executeTool(functionDetails, contextDetails)
    }

    suspend fun executeTool(request: FunctionGenerationRequest): FunctionGenerationResponse = FunctionGenerationResponse(executeTool(request.description, ""))

    private suspend fun executeTool(
        functionDetails: String,
        contextDetails: String,
    ): String {
        val generateFunctionPrompt =
            """
You are an expert OpenAI function-definition generator.

TASK  
• Read the text block marked "FUNCTION DESCRIPTION".  
• Produce **exactly one** JSON object with these top-level keys, in this order:  
  1. "name"– snake_case identifier inferred from the description (max 64 characters).  
  2. "description" – a concise, human-readable summary of what the function does.  
  3. "parameters"  – a Draft-07 JSON Schema describing the function’s arguments.

RULES FOR "parameters"  
• Must be an object containing "type", "properties", "required", and "additionalProperties".  
• Map every argument mentioned in the description to a property entry with:  
  – "type": one of "string", "number", "integer", "boolean", "array", or "object".  
  – "description": a short phrase explaining the argument.  
• If a property is always needed, list it in "required".  
• Use `"additionalProperties": false`.  
• Follow the JSON Schema spec exactly (Draft-07 by default).  
• Support nested objects, arrays, enums, length/range constraints, etc., when indicated.

OUTPUT FORMAT  
Return **pure JSON** – no markdown fences, no comments, no extra keys.

```json
{
  "name": "<inferred_snake_case_name>",
  "description": "<single-sentence summary>",
  "parameters": {
    "type": "object",
    "properties": {
      "<arg1>": { "type": "<type>", "description": "<desc>" },
      "<arg2>": { "type": "<type>", "description": "<desc>" }
    },
    "required": ["<arg1>", "<arg2>"],
    "additionalProperties": false
  }
}

CONSTRAINTS
• Preserve any given naming style (camelCase, kebab-case, snake_case).
• Do not invent arguments not implied by the description.
• Do not output anything except the single JSON object.

FUNCTION DESCRIPTION
$functionDetails

CONTEXT SUMMARY OF PROGRESS SO FAR
$contextDetails
            """.trimIndent()
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = listOf(mapOf("role" to "system", "content" to generateFunctionPrompt)),
                model = systemSettings.model,
                stream = false,
                store = false,
            )

        return modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.modelApiKey)
    }
}
