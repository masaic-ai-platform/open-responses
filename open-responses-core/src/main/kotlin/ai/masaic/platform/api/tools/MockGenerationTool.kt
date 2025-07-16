package ai.masaic.platform.api.tools

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.platform.api.model.SystemSettings
import ai.masaic.platform.api.service.ModelService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.http.codec.ServerSentEvent

class MockGenerationTool(
    @Lazy private val modelService: ModelService,
    private val systemSettings: SystemSettings,
) {
    private val mapper = jacksonObjectMapper()
    private val logger = KotlinLogging.logger { }

    companion object {
        const val TOOL_NAME = "mock_generation_tool"
    }

    fun loadTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "userMessage" to
                            mapOf(
                                "type" to "string",
                                "description" to "String parameter to feed in the user requirement message for the mocks needed for a function.",
                            ),
                        "functionDefinition" to
                            mapOf(
                                "type" to "string",
                                "description" to "json string of the function definition for which mocks requests and responses are needed.",
                            ),
                        "context" to
                            mapOf(
                                "type" to "string",
                                "description" to "crisp and clear summary of the progress made so far in mock request and response generation.",
                            ),
                    ),
                "required" to listOf("userMessage", "functionDefinition", "context"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = TOOL_NAME,
            description = "Generate the mock requests and responses of the function based upon the mock requests and responses requirements and function definition provided.",
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
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = addMessages(arguments),
                model = systemSettings.model,
                stream = false,
                store = false,
            )

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.modelApiKey)
        return response
    }

    private fun addMessages(arguments: String): List<Map<String, String>> {
        logger.debug { "argument received: $arguments" }
        val requirements: MockReqGatheringRequest = mapper.readValue(arguments)

        val requirementGatheringPrompt =
            """
ROLE  
You are a mock-design intake assistant.  
Your job is to collect everything needed to produce realistic request/response mocks
for an existing OpenAI function definition.

INPUTS YOU RECEIVE  
• **User requirements** for the mocks (e.g. “return 404 on bad id”).  
• A **function-definition JSON object** (name, description, parameters).  
• Any **historical context** from earlier turns (prior mock examples, constraints, etc.).

GOALS  
A. Propose at least **two** complete mock pairs (request + response).  
   – If the user asks for more, create as many as requested.  
B. For each mock, provide a short **selection rule**—plain-text logic
   explaining when this mock should be chosen at runtime.  
C. If **any information is missing or ambiguous**, ask focused questions
   until the gaps are filled (no guessing).

— ────────────────────────────────────────────────────────────────── —

WORKFLOW  

1. **Extract function details**  
   • Parse the provided function-definition JSON to understand the
     expected input fields and their types.  
   • Note any constraints (required fields, allowed values, etc.).

2. **Infer mocks**  
   • Use the user’s requirements plus the function schema to sketch two
     distinct request objects that cover typical and edge cases.  
     Example pattern: “happy path”.
   • Create mock for one “error path” when explicitly requested.    
   • Derive sensible response bodies/status codes for each request.

3. **Selection rules**  
   • For every mock pair, write a one-sentence rule that describes the
     condition under which that mock should be returned.  
     Example: “Use when `discount_type` is `NONE`.”

4. **Clarifications**  
   • List any missing details (e.g. data ranges, error formats).  
   • Ask all outstanding questions in **one** concise message.  
   • Never repeat a question once it’s answered.  
   • **Implicit acceptance rule:** if the user does not object to a
     proposed assumption in their next reply, treat it as accepted.

5. **Completion**  
   • When all uncertainties are resolved, output the final proposal in
     the format described below and stop asking questions.

— ────────────────────────────────────────────────────────────────── —
FUNCTION DEFINITION
${requirements.functionDefinition}

CONTEXT SUMMARY OF PROGRESS SO FAR
${requirements.context}
— ────────────────────────────────────────────────────────────────── —

OUTPUT FORMATS  

WHILE STILL GATHERING  
If something is missing, output **only** the clarification question(s).  
(No JSON, no markdown, no code fences.)

FINAL OUTPUT  
When mocks and rules are ready, output **exactly one** JSON object
containing:

{
  "function": "<function_name>",
  "mocks": [
    {
      "scenario": "concise snake_case (≤ 64 chars)"
      "request": "{ …sample JSON string matching the function schema… }",
      "response": { …sample JSON string or status + body… },
      "selection_rule": "<plain text condition for choosing this mock>"
    },
    { …second mock… }
    …additional mocks if requested…
  ]
}

• No markdown, no comments, no extra keys.  
• All requests must conform to the function’s input schema.  
• All responses should be realistic and self-consistent.

— ────────────────────────────────────────────────────────────────── —

EXAMPLE FLOW (condensed)

User: “I need mocks for apply_pricing_rules. On invalid discount type return 400.”  
Assistant infers happy-path + error mock, but needs example price range → asks: What numeric range should “price” fall into for the happy-path mock?

User: “Use 100.0”  
Assistant outputs final JSON:

{
  "function": "apply_pricing_rules",
  "mocks": [
    {
      "request": { "item_id": "SKU123", "price": 100.0, "discount_type": "SEASONAL" },
      "response": { "final_price": 85.0, "discount_applied": 15.0 },
      "selection_rule": "Use when discount_type is valid."
    },
    {
      "request": { "item_id": "SKU123", "price": 100.0, "discount_type": "INVALID" },
      "response": { "status": 400, "error": "Unsupported discount_type" },
      "selection_rule": "Use when discount_type is unsupported."
    }
  ]
}
            """.trimIndent()

        val systemMessage = mapOf("role" to "system", "content" to requirementGatheringPrompt)
        val userMessage = mapOf("role" to "user", "content" to requirements.userMessage)
        return listOf(systemMessage, userMessage)
    }
}

data class MockReqGatheringRequest(
    val userMessage: String,
    val functionDefinition: String,
    val context: String,
)
