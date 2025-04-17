package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.LabelModelGrader
import ai.masaic.openevals.api.model.SimpleInputMessage
import ai.masaic.openevals.api.model.TestingCriterion
import ai.masaic.openevals.api.utils.TemplateUtils
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mitchellbosecke.pebble.PebbleEngine
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.credential.BearerTokenCredential
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

            // Create the API client
            val openAIClient = createOpenAIClient(criterion.apiKey)
            
            // Call the model to get a classification
            val result = callLabelModel(openAIClient, criterion, processedInputs)
            
            logger.debug("Model returned label: $result")
            
            // Check if the result is in the passing labels
            val passed = criterion.passingLabels.contains(result)
            
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = passed,
                message = if (passed) {
                    "Model classification ($result) is in the passing labels: ${criterion.passingLabels}"
                } else {
                    "Model classification ($result) is not in the passing labels: ${criterion.passingLabels}"
                }
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
    private fun processInputs(inputs: List<SimpleInputMessage>, actualJson: String): List<SimpleInputMessage> {
        return inputs.map { input ->
            SimpleInputMessage(
                role = input.role,
                content = TemplateUtils.resolveTemplateValue(input.content, actualJson, pebbleEngine)
            )
        }
    }
    
    /**
     * Create an OpenAI client.
     *
     * @return An authenticated OpenAI client
     */
    private fun createOpenAIClient(apiKey: String): OpenAIClient {
        val baseURL = "https://api.openai.com/v1"
        return OpenAIOkHttpClient
            .builder()
            .credential(BearerTokenCredential.create { apiKey })
            .baseUrl(baseURL)
            .build()
    }
    
    /**
     * Call the label model to classify the inputs.
     *
     * @param client The OpenAI client
     * @param criterion The label model grader criterion containing model and labels
     * @param inputs The processed input messages
     * @return The selected label
     */
    private fun callLabelModel(
        client: OpenAIClient,
        criterion: LabelModelGrader,
        inputs: List<SimpleInputMessage>
    ): String {
        // Create completion params builder
        val completionBuilder = ChatCompletionCreateParams.builder().model(criterion.model)
        
        // Add input messages based on their roles
        inputs.forEach { message ->
            when {
                // System messages
                message.role.lowercase() == "system" || message.role.lowercase() == "developer" -> {
                    completionBuilder.addMessage(
                        ChatCompletionSystemMessageParam
                            .builder()
                            .content(message.content)
                            .build()
                    )
                }
                // User messages (or any other role)
                else -> {
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam
                            .builder()
                            .content(message.content)
                            .build()
                    )
                }
            }
        }
        
        // Call the API
        val completion = client.chat().completions().create(completionBuilder.build())
        
        // Extract and clean the response
        val response = completion
            .choices()
            .firstOrNull()
            ?.message()
            ?.content()
            ?.orElse("")
            ?.trim() ?: ""
        
        // Verify the response is a valid label
        return if (criterion.labels.contains(response)) {
            response
        } else {
            // If the model didn't return an exact label, try to find the closest match
            criterion.labels.find { response.contains(it, ignoreCase = true) } ?: criterion.labels.first()
        }
    }
}
