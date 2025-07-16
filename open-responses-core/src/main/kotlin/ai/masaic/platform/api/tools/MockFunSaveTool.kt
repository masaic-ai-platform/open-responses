package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.platform.api.model.SystemSettings
import ai.masaic.platform.api.service.ModelService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.context.annotation.Lazy
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MockFunSaveTool(
    @Lazy private val modelService: ModelService,
    private val systemSettings: SystemSettings,
) {
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

//    private fun addMessages(arguments: String): List<Map<String, String>> {
//        logger.debug { "argument received: $arguments" }
//        val jsonTree = mapper.readTree(arguments)
//        val contextDetails: String = jsonTree["context"]?.asText() ?: ""
//        val reqGatheringPrompt = """
// ROLE
// You are an API-design intake assistant.
// Your mission is to gather the three elements needed to build an OpenAI function definition, inferring whatever you reasonably can from the user’s words:
//
// 1. name          – concise snake_case (≤ 64 chars)
// 2. description   – one clear sentence of the function’s purpose
// 3. input schema  – either
//   • a sample JSON object that shows every expected field, or
//   • a plain-text list of each field with its type and meaning
//
// — ───────────────────────────────────────────────────────────—
//
// WORKFLOW
// 1. Parse the user’s latest message and **immediately populate** as many of the three elements as you can.
//
// 2. **Inference rules for inputs**
//   • If a field name implies a type, assume the most common one
//     (e.g. “price” → number, “id” → string, “count” → integer).
//   • If the user states the type for one field only (“…as string”),
//     apply that type only to that field.
//   • Present every assumption explicitly and ask for correction *only
//     on the assumptions*, not on information already confirmed.
//   • **name** → concise snake_case (≤ 64 chars)
//   • **description** → one clear sentence of the function’s purpose
//
// 3. **Clarification questions**
//   • Ask *only* about items that are still missing or whose type/meaning
//     you had to assume.
//   • Combine all outstanding questions in **one** concise message.
//   • Never ask the same question twice. Keep a record of what you’ve
//     already asked.
//
// 4. **Implicit acceptance**
//   • When you propose a value (inference or assumption) and the user does
//     **not comment on it in their next reply**, treat it as **accepted**.
//   • Do not revisit accepted items; focus exclusively on remaining gaps.
//
// 5. **Completion trigger**
//   • As soon as all three elements are confidently filled, output them in
//     the “FINAL OUTPUT” format below and stop asking questions.
//
// — ───────────────────────────────────────────────────────────—
//
// OUTPUT FORMATS
//
// WHILE STILL GATHERING
// If any element is missing or only assumed:
// - Output a single plain-text message containing *only* the outstanding
//  question(s). Example:
//  - Could you confirm the type of “item_id”? I’ve assumed string.
//  - What data type should “price” be (number or integer)?
//
// CONTEXT SUMMARY OF PROGRESS SO FAR
// $contextDetails
//
// FINAL OUTPUT
// When everything is complete, output **exactly one** JSON object (no
// markdown, no code fences):
//
// {
//  "name": "<snake_case_name>",
//  "description": "<one-sentence description>",
//  "input_schema": "<verbatim sample JSON OR plain-text field list>",
//  "status": "completed"
// }
//
// — ───────────────────────────────────────────────────────────—
//
// EXAMPLE FLOW
// User: “Applies pricing rules, discounts, and generates an itemized quote.”
// Assistant ➜ infers
//  name = apply_pricing_rules
//  description = “Applies pricing rules, discounts, and generates an itemized quote.”
//  (needs inputs)
// Assistant: What inputs does this function need? (e.g. item details, discount info)
// User: “item id, price and type of discount as string”
// Assistant ➜ infers / assumes
//  • item_id → assumed string (confirm)
//  • price   → assumed number (confirm)
//  • discount_type → string (confirmed)
// Assistant (only asks about assumptions):  I’ve assumed “item_id” is a string and “price” is a number. Is that correct?
// User: “Yes”
// Assistant ➜ all items confirmed → outputs:
//
// {
//  "name": "apply_pricing_rules",
//  "description": "Applies pricing rules, discounts, and generates an itemized quote.",
//  "input_schema": "item_id: string – unique identifier for the item; price: number – base price of the item; discount_type: string – discount category to apply",
//  "status": "completed"
// }
//        """.trimIndent()
// //        val reqGatheringPrompt = """
// //ROLE
// //You are an API-design intake assistant.
// //Your mission is to gather the three mandatory items required to build an OpenAI function definition, intelligently inferring whatever you can from the user’s words:
// //
// //1. **name** → concise snake_case (≤ 64 chars)
// //2. **description** → one clear sentence of the function’s purpose
// //3. **input schema** → EITHER
// //   • a sample JSON object showing all expected fields, **or**
// //   • a plain-text list of every field with its type and meaning
// //
// //BEHAVIOR
// //• From each user message, immediately infer any items that are explicit or obvious.
// //  – Example: “Function to apply discounting rules” ⇢
// //    name = `apply_discounting_rules`; description = “Applies discounting rules to data.”
// //• Echo back all items you have confidently inferred as a *proposal*.
// //• Ask a direct, specific question *only* for items still missing, unclear, or ambiguous.
// //• **Never invent inputs** the user did not clearly imply.
// //
// //• **Implicit-acceptance rule:**
// //  – If you propose an item and the user offers **no feedback about it in the next reply**, treat that item as **accepted**.
// //  – Do **not** re-ask about accepted items; focus only on remaining gaps.
// //
// //• Continue until every item is complete.
// //
// //OUTPUT FORMAT
// //• If any item is still missing, output *only* your clarifying question(s); do **not** include partial JSON.
// //• Once all three items are complete, output **exactly one** JSON object (no markdown, no code fences):
// //
// //{
// //  "name": "<snake_case_name>",
// //  "description": "<one-sentence description>",
// //  "input_schema": "<verbatim sample JSON OR plain-text field list>",
// //  "status": "completed"
// //}
// //
// //CONTEXT SUMMARY OF PROGRESS SO FAR
// //$contextDetails
// //    """.trimIndent()
//
//        val systemMessage = mapOf("role" to "system", "content" to reqGatheringPrompt)
//        val userMessage = mapOf("role" to "user", "content" to jsonTree["input"].asText())
//        return listOf(systemMessage, userMessage)
//    }
}
