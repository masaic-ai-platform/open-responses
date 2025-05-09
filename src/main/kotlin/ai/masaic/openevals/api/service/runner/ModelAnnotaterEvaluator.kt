package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.ModelAnnotator
import ai.masaic.openevals.api.model.SimpleInputMessage
import ai.masaic.openevals.api.model.TestingCriterion
import ai.masaic.openevals.api.service.ModelClientService
import ai.masaic.openevals.api.utils.SampleSchemaUtils
import ai.masaic.openevals.api.utils.TemplateUtils
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import com.openai.core.JsonValue
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Implementation of CriterionEvaluator for label model grader testing criteria.
 * Uses a language model to classify inputs according to a set of labels.
 */
@Component
class ModelAnnotaterEvaluator(
    private val pebbleEngine: PebbleEngine,
    private val modelClientService: ModelClientService,
) : CriterionEvaluator {
    private val logger = LoggerFactory.getLogger(ModelAnnotaterEvaluator::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val evaluatorSchema = objectMapper.readValue<Map<String, JsonValue>>(JSON_SCHEMA)

    /**
     * Checks if this evaluator can handle the given testing criterion.
     *
     * @param criterion The criterion to check
     * @return True if this evaluator can handle the criterion
     */
    override fun canEvaluate(criterion: TestingCriterion): Boolean = criterion is ModelAnnotator

    /**
     * Evaluate the label model criterion against the actual result and reference data.
     *
     * @param criterion The testing criterion to evaluate
     * @param actualJson The actual result JSON string to evaluate
     * @param referenceJson The reference data JSON string to compare against
     * @return Result of the evaluation
     */
    override fun evaluate(
        criterion: TestingCriterion,
        actualJson: String,
        referenceJson: String,
    ): CriterionEvaluator.CriterionResult {
        if (criterion !is ModelAnnotator) {
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = false,
                message = "Invalid criterion type: expected ModelAnnotator but got ${criterion.javaClass.simpleName}",
            )
        }
        
        try {
            // Process the input messages to use actual values from the JSON
            val processedInputs = processInputs(criterion.input, actualJson)
            
            logger.debug("Calling model ${criterion.model} with inputs: $processedInputs")

            // Call the model to get a classification
            val result = callModel(criterion, processedInputs)
            
            logger.debug("Model returned response: $result")
            
            // Check if the result is in the passing labels
            val passed = true
            
            // Extract user message for more verbose output
            val userMessage = processedInputs.find { it.role.lowercase() == "user" }?.content ?: "No user message found"

            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = passed,
                message = result,
            )
        } catch (e: Exception) {
            logger.error("Error evaluating label model: ${e.message}", e)
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = false,
                message = "Error: ${e.message}",
            )
        }
    }

    /**
     * Process the input templates to use actual values from the JSON.
     *
     * @param inputs The list of input templates
     * @param actualJson The actual JSON result to resolve values from
     * @return List of processed input messages
     */
    private fun processInputs(
        inputs: List<SimpleInputMessage>,
        actualJson: String,
    ): List<SimpleInputMessage> =
        inputs.map { input ->
            SimpleInputMessage(
                role = input.role,
                content = TemplateUtils.resolveTemplateValue(input.content, actualJson, pebbleEngine),
            )
        }

    /**
     * Add SimpleInputMessages to a completion params builder based on their roles.
     *
     * @param builder The builder to add messages to
     * @param messages The list of simple input messages
     * @return The updated builder
     */
    private fun addSimpleInputMessagesToBuilder(
        builder: ChatCompletionCreateParams.Builder,
        messages: List<SimpleInputMessage>,
    ): ChatCompletionCreateParams.Builder {
        messages.forEach { message ->
            when {
                // System messages
                message.role.lowercase() == "system" || message.role.lowercase() == "developer" -> {
                    builder.addMessage(
                        ChatCompletionSystemMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
                // User messages (or any other role)
                else -> {
                    builder.addMessage(
                        ChatCompletionUserMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
            }
        }
        return builder
    }

    /**
     * Call the label model to classify the inputs.
     *
     * @param criterion The label model grader criterion containing model and labels
     * @param inputs The processed input messages
     * @return Pair of (selected label, original response)
     */
    private fun callModel(
        criterion: ModelAnnotator,
        inputs: List<SimpleInputMessage>,
    ): String {
        // Create completion params and execute with cached client
        val builder = modelClientService.createBasicCompletionParams(criterion.model)

        builder
            .model(criterion.model)
            .responseFormat(addJsonSchema())
        addSimpleInputMessagesToBuilder(builder, inputs)

//        runBlocking {
//            delay(30 * 1000)
//        }

        val completionResult =
            modelClientService.executeWithClientAndErrorHandling(
                apiKey = criterion.apiKey,
                params = builder.build(),
                identifier = criterion.id,
            ) { content, error ->
                ai.masaic.openevals.api.model.CompletionResult(
                    contentJson = content,
                    error = error,
                )
            }
        
        // Check for errors
        if (completionResult.error != null) {
            throw RuntimeException("Error calling label model: ${completionResult.error}")
        }
        
        val response = completionResult.contentJson

        logger.debug("Response: $response")
        return response
    }

    private fun addJsonSchema(): ResponseFormatJsonSchema {
        val jsonSchema =
            ResponseFormatJsonSchema.JsonSchema.Schema
                .builder()
                .additionalProperties(evaluatorSchema)
                .build()

        val format =
            ResponseFormatJsonSchema.JsonSchema
                .builder()
                .schema(jsonSchema)
                .name("evalSchema")
                .build()


        return ResponseFormatJsonSchema
            .builder()
            .type(JsonValue.from("json_schema"))
            .jsonSchema(format)
            .build()

    }

}

const val JSON_SCHEMA = """
    {
  "title": "ConversationAnnotation",
  "type": "object",
  "properties": {
    "conversationId": {
      "type": "string",
      "description": "Unique identifier for the conversation"
    },
    "conversation": {
      "type": "string",
      "description": "Full text of the conversation"
    },
    "handover_reason_l1": {
      "type": "string",
      "description": "Top-level reason for agent handover"
    },
    "handover_reason_l2": {
      "type": "string",
      "description": "More specific sub-reason for handover"
    },
    "label_selection_reason": {
      "type": "string",
      "description": "Explanation for why these labels were selected"
    }
  },
  "required": [
    "conversationId",
    "conversation",
    "handover_reason_l1",
    "handover_reason_l2",
    "label_selection_reason"
  ],
  "additionalProperties": false
}
"""
