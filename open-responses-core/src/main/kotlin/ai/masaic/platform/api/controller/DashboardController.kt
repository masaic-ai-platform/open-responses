package ai.masaic.platform.api.controller

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.api.model.MCPTool
import ai.masaic.openresponses.tool.*
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import ai.masaic.platform.api.model.*
import ai.masaic.platform.api.service.ModelService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/dashboard")
@CrossOrigin("*")
class DashboardController(
    private val toolService: ToolService,
    private val mcpToolExecutor: MCPToolExecutor,
    private val modelService: ModelService
) {
    private val mapper = jacksonObjectMapper()
    private val systemSettings = SystemSettings()
    private lateinit var modelProviders: Set<ModelProvider>

    init {
        val resource = ClassPathResource("model-providers.json")
        val jsonContent = resource.inputStream.bufferedReader().use { it.readText() }
        val providersList: List<ModelProvider> = mapper.readValue(jsonContent)
        modelProviders = providersList.toSet()
    }

    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getModelProviders(): ResponseEntity<Set<ModelProvider>> {
        return ResponseEntity.ok(modelProviders)
    }

    @PostMapping("/generate/schema", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun generateSchema(@RequestBody request: SchemaGenerationRequest): ResponseEntity<SchemaGenerationResponse> {
        val generateSchemaPrompt = """
You are an expert JSON Schema generator.

TASK  
• Read the incoming plain-language block (marked “SCHEMA DESCRIPTION”).  
• Produce **exactly one** JSON object with these top-level keys, in this order:  
  1. "name"– kebab-case or snake_case identifier for the schema you infer.  
  2. "schema"– a valid Draft-07 JSON Schema describing the response.  
  3. "strict"– the literal boolean true.

RULES FOR "schema" VALUE  
• Must be an object containing "type", "properties", "re    quired", and "additionalProperties".  
• Infer property names, types, and per-property "description" strings from the description text.  
• Include every property mentioned; list them all under "required".  
• Set `"additionalProperties": false`.  
• Follow the JSON-Schema spec at https://json-schema.org/ (Draft-07 defaults).  
• Do **not** add any keys not defined by the spec.

OUTPUT FORMAT  
```json
{
  "name": "<inferred_name>",
  "schema": {
    ...draft-07 schema here...
  },
  "strict": true
}

CONSTRAINTS
• Return pure JSON – no markdown fences, no comments, no extra text.
• Preserve camelCase, snake_case, or kebab-case exactly as given in the description.
• If the description mentions enums, arrays, nested objects, or numeric ranges, map them to the appropriate JSON-Schema constructs.

SCHEMA DESCRIPTION
${request.description}
        """.trimIndent()

        val createCompletionRequest =
            CreateCompletionRequest(
                messages = listOf(mapOf("role" to "system", "content" to generateSchemaPrompt)),
                model = systemSettings.openAiModel,
                stream = false,
                store = false,
            )

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.openAiApiKey)
        return ResponseEntity.ok(SchemaGenerationResponse(response))
    }

    @PostMapping("/generate/function", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun generateFunction(@RequestBody request: FunctionGenerationRequest): ResponseEntity<FunctionGenerationResponse> {
        val generateFunctionPrompt = """
You are an expert OpenAI function-definition generator.

TASK  
• Read the text block marked "FUNCTION DESCRIPTION".  
• Produce **exactly one** JSON object with these top-level keys, in this order:  
  1. "name"  – snake_case identifier inferred from the description (max 64 characters).  
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
${request.description}
        """.trimIndent()
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = listOf(mapOf("role" to "system", "content" to generateFunctionPrompt)),
                model = systemSettings.openAiModel,
                stream = false,
                store = false,
            )

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.openAiApiKey)
        return ResponseEntity.ok(FunctionGenerationResponse(response))

    }

    @PostMapping("/mcp/list_actions")
    suspend fun listMcpActions(@RequestBody mcpListToolsRequest: McpListToolsRequest): ResponseEntity<List<FunctionTool>> {
        val tools = toolService.getRemoteMcpTools(
            mcpTool = MCPTool(
                type = "mcp",
                serverLabel = mcpListToolsRequest.serverLabel,
                serverUrl = mcpListToolsRequest.serverUrl,
                headers = mcpListToolsRequest.headers
            )
        )

        val updatedTools = tools.map {
            it.copy(name = it.name?.replace("${mcpListToolsRequest.serverLabel}_",""))
        }
        return ResponseEntity.ok(updatedTools)
    }

    @GetMapping("/tools", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTools(): ResponseEntity<List<ToolMetadata>> =
        toolService.listAvailableTools().let {
            ResponseEntity.ok(it)
        }

    @PostMapping("/tools/mcp/execute", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun executeMCPTool(
        @RequestBody toolRequest: ExecuteToolRequest,
    ): String {
        val toolDefinition = toolService.findToolByName(toolRequest.name) ?: return "Tool ${toolRequest.name} not found."
        return mcpToolExecutor.executeTool(toolDefinition, jacksonObjectMapper().writeValueAsString(toolRequest.arguments)) ?: "no response from ${toolRequest.name}"
    }
}

data class ExecuteToolRequest(
    val name: String,
    val arguments: Map<String, Any>,
)
