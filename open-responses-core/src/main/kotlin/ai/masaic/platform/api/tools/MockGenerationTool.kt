package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.openresponses.tool.mcp.nativeToolDefinition
import ai.masaic.platform.api.config.ModelSettings
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.service.messages
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.http.codec.ServerSentEvent

class MockGenerationTool(
    @Lazy private val modelService: ModelService,
    private val modelSettings: ModelSettings,
) : ModelDepPlatformNativeTool(PlatformToolsNames.MOCK_GEN_TOOL, modelService, modelSettings) {
    private val logger = KotlinLogging.logger { }

    override fun provideToolDef(): NativeToolDefinition =
        nativeToolDefinition {
            name(toolName)
            description(
                "Generate the mock requests and responses of the function based upon the " +
                    "mock requests and responses requirements and function definition provided.",
            )
            parameters {
                property(
                    name = "userMessage",
                    type = "string",
                    description = "String parameter to feed in the user requirement message for the mocks needed for a function.",
                    required = true,
                )
                property(
                    name = "functionBody",
                    type = "string",
                    description = "JSON string of the function body for which mock requests and responses are needed.",
                    required = true,
                )
                property(
                    name = "outputSchema",
                    type = "string",
                    description = "JSON string of the output schema object this function returns.",
                    required = true,
                )
                property(
                    name = "context",
                    type = "string",
                    description = "Chain of user and assistant messages happened so far for mock request and response generation.",
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
    ): String = callModel(paramsAccessor, client, addMessages(arguments))

    private fun addMessages(arguments: String): List<Map<String, String>> {
        logger.debug { "argument received: $arguments" }
        val requirements: MockReqGatheringRequest = mapper.readValue(arguments)
        val requirementGatheringPrompt =
            """
ROLE  
You are a mock-design intake assistant.  
Your job is to collect everything needed to produce request/response mocks
for an OpenAI function definition provided to you.

INPUTS YOU RECEIVE  
• **User requirements** for the mocks (e.g. “return 404 on bad id”).  
• A **function-definition JSON object** (name, description, parameters).  
• Any **historical conversation context** from earlier turns (prior mock examples, constraints, etc.).

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
   • Parse the provided functionBody JSON to understand the
     expected input fields and their types.  
   • Note any constraints (required fields, allowed values, etc.).

2. **Extract output schema**  
   • Parse the provided outputSchema JSON to understand the
     expected output fields.  

3. **Infer mocks**  
   • Use the user’s requirements plus the function schema to sketch two
     distinct request objects that cover typical cases.  
     Example pattern: “happy path”.
   • Create mock for one “error path” when explicitly requested.    
   • Based upon user inputs, derive sensible response for each request.

3. **Selection rules**  
   • For every mock pair, write a one-sentence rule that describes the
     condition under which that mock should be returned.  
     Example: “Use when `discount_type` is `NONE`.”

4. **Clarifications**  
   • List any missing details (e.g. data ranges, error formats).  
   • Ask all outstanding questions in bullet points.  
   • Never repeat a question once it’s answered.  
   • **Implicit acceptance rule:** if the user does not object to a
     proposed assumption in their next reply, treat it as accepted.

5. **Completion**  
   • When all uncertainties are resolved, output the final proposal in
     the format described below and stop asking questions.

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
FUNCTION DEFINITION
${requirements.functionBody}

OUTPUT SCHEMA
${requirements.outputSchema}

CONTEXT SUMMARY OF PROGRESS SO FAR
${requirements.context}

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

        return messages {
            systemMessage(requirementGatheringPrompt)
            userMessage(requirements.userMessage)
        }
    }
}

data class MockReqGatheringRequest(
    val userMessage: String,
    val functionBody: String,
    val outputSchema: String,
    val context: String,
)
