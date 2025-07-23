package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.openresponses.tool.mcp.nativeToolDefinition
import ai.masaic.platform.api.config.ModelSettings
import ai.masaic.platform.api.model.FunctionGenerationRequest
import ai.masaic.platform.api.model.FunctionGenerationResponse
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.service.messages
import com.openai.client.OpenAIClient
import org.springframework.context.annotation.Lazy
import org.springframework.http.codec.ServerSentEvent

class FunDefGenerationTool(
    @Lazy private val modelService: ModelService,
    private val modelSettings: ModelSettings,
) : ModelDepPlatformNativeTool(PlatformToolsNames.FUN_DEF_GEN_TOOL, modelService, modelSettings) {
    override fun provideToolDef(): NativeToolDefinition =
        nativeToolDefinition {
            name(toolName)
            description("Generate the definition of the function based upon the details provided.")
            parameters {
                property(
                    name = "functionDetails",
                    type = "string",
                    description = "String parameter that accepts details of the function to be generated. Can accept raw JSON strings.",
                    required = true,
                )
                property(
                    name = "context",
                    type = "string",
                    description = "Crisp and clear summary of the progress made so far in function definition generation.",
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
    ): String {
        val jsonTree = mapper.readTree(arguments)
        val contextDetails: String = jsonTree["context"]?.asText() ?: ""
        val functionDetails: String = jsonTree["functionDetails"]?.asText() ?: throw IllegalStateException("function details for definition generation are necessary.")
        val messages = addMessages(functionDetails, contextDetails)
        return callModel(paramsAccessor, client, messages)
    }

    suspend fun executeTool(
        request: FunctionGenerationRequest,
        settings: ModelSettings,
    ): FunctionGenerationResponse {
        val messages = addMessages(request.description, "")
        val response = callModel(settings, messages)
        val functionBody: String = mapper.readTree(response)["functionBody"].asText()
        return FunctionGenerationResponse(functionBody)
    }

    private suspend fun addMessages(
        functionDetails: String,
        contextDetails: String,
    ): List<Map<String, String>> {
        val generateFunctionPrompt =
            """
You are an expert OpenAI function-definition generator.

TASK  
• Read the text block marked "FUNCTION DESCRIPTION".  
• Produce **exactly one** JSON object with functionBody having these top-level keys, in this order:  
  1. "name"– snake_case identifier inferred from the description (max 64 characters).  
  2. "description" – a concise, human-readable summary of what the function does.  
  3. "parameters"  – a Draft-07 JSON Schema describing the function’s arguments.
  and JSON outputSchema object this function can return

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
  "functionBody": {
    "name": "<inferred_snake_case_name>",
    "description": "<single-sentence summary>",
    "parameters": {
      "type": "object",
      "properties": {
        "<arg1>": {
          "type": "<type>",
          "description": "<desc>"
        },
        "<arg2>": {
          "type": "<type>",
          "description": "<desc>"
        }
      },
      "required": [
        "<arg1>",
        "<arg2>"
      ],
      "additionalProperties": false
    }
  },
  "outputSchema": {
        //output object
  }
}
```

CONSTRAINTS
• Preserve any given naming style (camelCase, kebab-case, snake_case).
• Do not invent arguments not implied by the description.
• Do not output anything except the single JSON object.

FUNCTION DESCRIPTION
$functionDetails

CONTEXT SUMMARY OF PROGRESS SO FAR
$contextDetails
            """.trimIndent()
        return messages {
            systemMessage(generateFunctionPrompt)
            userMessage("Generate function.")
        }
    }
}
