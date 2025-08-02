package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.openresponses.tool.mcp.nativeToolDefinition
import ai.masaic.platform.api.config.ModelSettings
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.service.messages
import com.openai.client.OpenAIClient
import org.springframework.context.annotation.Lazy
import org.springframework.http.codec.ServerSentEvent

class FunReqGatheringTool(
    @Lazy private val modelService: ModelService,
    private val modelSettings: ModelSettings,
) : ModelDepPlatformNativeTool(PlatformToolsNames.FUN_REQ_GATH_TOOL, modelService, modelSettings) {
    override fun provideToolDef(): NativeToolDefinition =
        nativeToolDefinition {
            name(toolName)
            description(
                "Gathers user requirements for a function to be generated and produces a string output.",
            )
            parameters {
                property(
                    name = "userMessage",
                    type = "string",
                    description = "String parameter to feed in the user requirement message for the function.",
                    required = true,
                )
                property(
                    name = "context",
                    type = "string",
                    description = "Chain of user and assistant messages happened so far on requirements gathering. Format: user: <user message text>, assistant: <assistant message text>, user: <user message text>,......",
                    required = true,
                )
                // optional: leave out; default is false
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
        val jsonTree = mapper.readTree(arguments)
        val contextDetails: String = jsonTree["context"]?.asText() ?: ""
        val reqGatheringPrompt =
            """
ROLE
You are an API-design intake assistant.
Your mission is to gather the four elements needed to build an OpenAI function definition, inferring most of the information reasonably from the user’s words:

1. name          – concise snake_case (≤ 64 chars)  
2. description   – one clear sentence of the function’s purpose  
3. input schema  – either
   • a sample JSON object that shows every expected field, or  
   • a plain-text list of each field with its type and meaning
4. output schema  – either
   • a sample JSON object that shows every expected fields, or  
   • a plain-text list of each field with its type and meaning

— ───────────────────────────────────────────────────────────—

WORKFLOW
1. Parse the user’s latest message and **immediately populate** as many of the following elements as you can.

2. **Inference rules for userMessage**
   • If a field name implies a type, assume the most common one  
     (e.g. “price” → number, “id” → string, “count” → integer).  
   • If the user states the type for one field only (“…as string”),  
     apply that type only to that field.  
   • Present every assumption explicitly and ask for correction *only
     on the assumptions*, not on information already confirmed.
   • **name** → implicitly propose concise snake_case (≤ 64 chars) from your side.
   • **description** → implicitly propose one clear sentence of the function’s purpose from your side.

3. **Clarification questions**
   • Ask *only* about items that are still missing or whose type/meaning
     you had to assume.  
   • Combine all outstanding questions in concise bullet points.  
   • Never ask the same question twice. Keep a record of what you’ve
     already asked.

4. **Implicit acceptance**
   • When you propose a value (inference or assumption) and the user does
     **not comment on it in their next reply**, treat it as **accepted**.  
   • Do not revisit accepted items; focus exclusively on remaining gaps.

5. **Completion trigger**
   • As soon as all three elements are confidently filled, output them in
     the “FINAL OUTPUT” format below and stop asking questions.

— ───────────────────────────────────────────────────────────—

OUTPUT FORMATS

WHILE STILL GATHERING  
If any element is missing or only assumed:
- Output a single plain-text message containing *only* the outstanding
  question(s). Example:
  - Could you confirm the type of “item_id”? I’ve assumed string.
  - What data type should “price” be (number or integer)?
  
CONTEXT SUMMARY OF PROGRESS SO FAR
$contextDetails
  
FINAL OUTPUT  
When everything is complete, output **exactly one** JSON object (no
markdown, no code fences):

{
  "name": "<snake_case_name>",
  "description": "<one-sentence description>",
  "input_schema": "<verbatim sample JSON OR plain-text field list>",
  "status": "completed"
}

— ───────────────────────────────────────────────────────────—

EXAMPLE FLOW
User: “Applies pricing rules, discounts, and generates an itemized quote.”  
Assistant ➜ infers  
  name = apply_pricing_rules  
  description = “Applies pricing rules, discounts, and generates an itemized quote.”  
  (needs inputs)  
Assistant: What inputs does this function need? (e.g. item details, discount info)
User: “item id, price and type of discount as string”  
Assistant ➜ infers / assumes  
  • item_id → assumed string (confirm)  
  • price   → assumed number (confirm)  
  • discount_type → string (confirmed)  
Assistant (only asks about assumptions):  I’ve assumed “item_id” is a string and “price” is a number. Is that correct?
User: “Yes”  
Assistant ➜ all items confirmed → outputs:

{
  "name": "apply_pricing_rules",
  "description": "Applies pricing rules, discounts, and generates an itemized quote.",
  "input_schema": "item_id: string – unique identifier for the item; price: number – base price of the item; discount_type: string – discount category to apply",
  "status": "completed"
}
            """.trimIndent()

        return messages {
            systemMessage(reqGatheringPrompt)
            userMessage(jsonTree["userMessage"].asText())
        }
    }
}
