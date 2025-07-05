package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.service.ModelClientService
import ai.masaic.openevals.api.utils.SampleSchemaUtils
import ai.masaic.openevals.api.utils.TemplateUtils
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
class LabelModelGraderEvaluator(
    private val pebbleEngine: PebbleEngine,
    private val modelClientService: ModelClientService,
) : CriterionEvaluator {
    private val logger = LoggerFactory.getLogger(LabelModelGraderEvaluator::class.java)
    private val objectMapper = jacksonObjectMapper()

    /**
     * Checks if this evaluator can handle the given testing criterion.
     *
     * @param criterion The criterion to check
     * @return True if this evaluator can handle the criterion
     */
    override fun canEvaluate(criterion: TestingCriterion): Boolean = criterion is LabelModelGrader

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
        if (criterion !is LabelModelGrader) {
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = false,
                message = "Invalid criterion type: expected LabelModelGrader but got ${criterion.javaClass.simpleName}",
            )
        }
        
        try {
            // Process the input messages to use actual values from the JSON
            val processedInputs = processInputs(criterion.input, actualJson)
            
            logger.debug("Calling model ${criterion.model} with inputs: $processedInputs")

            // Call the model to get a classification
            val result = callLabelModel(criterion, processedInputs)
            val (label, rawResponse) = result
            
            logger.debug("Model returned label: $label, from response: $rawResponse")
            
            // Check if the result is in the passing labels
            val passed = criterion.passingLabels.contains(label)
            
            // Extract user message for more verbose output
            val userMessage = processedInputs.find { it.role.lowercase() == "user" }?.content ?: "No user message found"
            
            // Find message with assistant content to display
            val assistantContent =
                processedInputs
                    .find { it.role.lowercase() == "assistant" }
                    ?.content
                    ?.let { "Assistant: '$it'" } ?: ""
            
            // Create a shortened version of the raw response for the message
            val shortResponse =
                if (rawResponse.length > 50) {
                    "${rawResponse.substring(0, 50)}..." 
                } else {
                    rawResponse
                }
            
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = passed,
                message =
                    if (passed) {
                        "Label check passed: model classified as '$label' which is in passing labels ${criterion.passingLabels}. User message: '$userMessage' $assistantContent. Raw response: '$shortResponse'"
                    } else {
                        "Label check failed: model classified as '$label' which is not in passing labels ${criterion.passingLabels}. User message: '$userMessage' $assistantContent. Raw response: '$shortResponse'"
                    },
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
    private fun callLabelModel(
        criterion: LabelModelGrader,
        inputs: List<SimpleInputMessage>,
    ): Pair<String, String> {
        // Create completion params and execute with cached client
        val builder = modelClientService.createBasicCompletionParams(criterion.model)
        // Create JSON schema for response format
        val jsonSchema =
            ResponseFormatJsonSchema.JsonSchema.Schema
                .builder()
                .additionalProperties(SampleSchemaUtils.schemaForModelLabeler())
                .build()

        val format =
            ResponseFormatJsonSchema.JsonSchema
                .builder()
                .schema(jsonSchema)
                .name("evalSchema")
                .build()

        // Create the builder with basic properties
        builder
            .model(criterion.model)
            .responseFormat(
                ResponseFormatJsonSchema
                    .builder()
                    .type(JsonValue.from("json_schema"))
                    .jsonSchema(format)
                    .build(),
            )

        addSimpleInputMessagesToBuilder(builder, inputs)
        
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
        
        logger.debug("Response from model: $response")
        
        // Extract the label from the nested JSON structure
        val extractedLabel =
            try {
                val jsonNode = objectMapper.readTree(response)
                val labelNode = jsonNode.path("item").path("label")
                if (labelNode.isMissingNode) {
                    logger.warn("Unable to find 'item.label' in response: $response")
                    response // Fall back to using full response if we can't extract label
                } else {
                    labelNode.asText()
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse JSON response: ${e.message}", e)
                response // Fall back to using full response if parsing fails
            }
        
        logger.debug("Extracted label: $extractedLabel")
        
        // Verify the response is a valid label
        val finalLabel =
            if (criterion.labels.contains(extractedLabel)) {
                extractedLabel
            } else {
                // If the model didn't return an exact label, try to find the closest match
                criterion.labels.find { extractedLabel.contains(it, ignoreCase = true) } 
                    ?: criterion.labels.first().also { 
                        logger.warn("Could not match extracted label '$extractedLabel' to any of the expected labels: ${criterion.labels}")
                    }
            }
        
        return Pair(finalLabel, response)
    }
}
