package ai.masaic.platform.api.controller

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.api.model.MCPTool
import ai.masaic.openresponses.tool.*
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import ai.masaic.platform.api.config.SystemSettings
import ai.masaic.platform.api.model.*
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.tools.FunDefGenerationTool
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Profile("platform")
@RestController
@RequestMapping("/v1/dashboard")
@CrossOrigin("*")
class DashboardController(
    private val toolService: ToolService,
    private val mcpToolExecutor: MCPToolExecutor,
    private val modelService: ModelService,
    private val systemSettings: SystemSettings,
    private val funDefGenerationTool: FunDefGenerationTool,
) {
    private val mapper = jacksonObjectMapper()
    private lateinit var modelProviders: Set<ModelProvider>

    init {
        val resource = ClassPathResource("model-providers.json")
        val jsonContent = resource.inputStream.bufferedReader().use { it.readText() }
        val providersList: List<ModelProvider> = mapper.readValue(jsonContent)
        modelProviders = providersList.toSet()
    }

    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getModelProviders(): ResponseEntity<Set<ModelProvider>> = ResponseEntity.ok(modelProviders)

    @PostMapping("/generate/schema", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun generateSchema(
        @RequestBody request: SchemaGenerationRequest,
    ): ResponseEntity<SchemaGenerationResponse> {
        val generateSchemaPrompt =
            """
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
                model = systemSettings.model,
                stream = false,
                store = false,
            )

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.modelApiKey)
        return ResponseEntity.ok(SchemaGenerationResponse(response))
    }

    @PostMapping("/generate/function", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun generateFunction(
        @RequestBody request: FunctionGenerationRequest,
    ): ResponseEntity<FunctionGenerationResponse> {
        val response = funDefGenerationTool.executeTool(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/generate/prompt", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun generatePrompt(
        @RequestBody request: PromptGenerationRequest,
    ): ResponseEntity<PromptGenerationResponse> {
        val generatePromptMetaPrompt =
            """
You are an elite prompt engineer.

OVERVIEW  
You will receive up to three labelled blocks:

  • TASK DESCRIPTION – plain-language explanation of what the model should do or mentions explicit edits the user wants.
  • EXISTING PROMPT   – (optional) the current prompt to be refined.   

YOUR JOB  
► If an EXISTING PROMPT is present, update it so that all TASK DESCRIPTION are satisfied **while preserving the original structure and intent wherever not contradicted**.  
► If no EXISTING PROMPT is supplied, craft a brand-new prompt that fulfils the TASK DESCRIPTION.  
► Always return **exactly one** finished execution prompt, ready for a language model to follow.

RECOMMENDED PROMPT STRUCTURE  
1. **Title line** – one concise sentence summarising the task.  
2. **Steps** – 3-7 bullet points, each starting with an imperative verb (Accept…, Validate…, Compute…).  
3. **Output format** – short block describing the required output.  
4. **Examples** – at least two `Input:` / `Output:` pairs.  
5. **Reminder** – a bold sentence beginning “Reminder:” that restates any critical rule(s).

STYLE & RULES  
• Use clear, unambiguous English and markdown bullets/headings.  
• Incorporate every constraint from TASK DESCRIPTION.    
• Do **not** output anything except the finished execution prompt—no commentary, fences, or JSON.

INPUT BLOCKS  
────────────────────────────────────────
TASK DESCRIPTION  
${request.description}

EXISTING PROMPT   (optional)
${request.existingPrompt}
────────────────────────────────────────
            """.trimIndent()
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = listOf(mapOf("role" to "system", "content" to generatePromptMetaPrompt)),
                model = systemSettings.model,
                stream = false,
                store = false,
            )

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.modelApiKey)
        return ResponseEntity.ok(PromptGenerationResponse(response))
    }

    @PostMapping("/mcp/list_actions")
    suspend fun listMcpActions(
        @RequestBody mcpListToolsRequest: McpListToolsRequest,
    ): ResponseEntity<List<FunctionTool>> {
        val tools =
            toolService.getRemoteMcpTools(
                mcpTool =
                    MCPTool(
                        type = "mcp",
                        serverLabel = mcpListToolsRequest.serverLabel,
                        serverUrl = mcpListToolsRequest.serverUrl,
                        headers = mcpListToolsRequest.headers,
                    ),
            )

        val updatedTools =
            tools.map {
                it.copy(name = it.name?.replace("${mcpListToolsRequest.serverLabel}_", ""))
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
