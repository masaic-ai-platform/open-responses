package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.StringCheckGrader
import ai.masaic.openevals.api.model.TestingCriterion
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.StringWriter

/**
 * Implementation of CriterionEvaluator for string check testing criteria.
 */
@Component
class StringCheckEvaluator(
    private val pebbleEngine: PebbleEngine
) : CriterionEvaluator {
    private val logger = LoggerFactory.getLogger(StringCheckEvaluator::class.java)

    /**
     * Checks if this evaluator can handle the given testing criterion.
     *
     * @param criterion The criterion to check
     * @return True if this evaluator can handle the criterion
     */
    override fun canEvaluate(criterion: TestingCriterion): Boolean {
        return criterion is StringCheckGrader
    }

    /**
     * Evaluate the string check criterion against the actual result and reference data.
     *
     * @param criterion The testing criterion to evaluate
     * @param actualJson The actual result JSON string to evaluate
     * @param referenceJson The reference data JSON string to compare against
     * @return Result of the evaluation
     */
    override fun evaluate(
        criterion: TestingCriterion,
        actualJson: String,
        referenceJson: String
    ): CriterionEvaluator.CriterionResult {
        if (criterion !is StringCheckGrader) {
            return CriterionEvaluator.CriterionResult(
                passed = false,
                message = "Invalid criterion type: expected StringCheckGrader but got ${criterion.javaClass.simpleName}"
            )
        }
        
        try {
            // Use Pebble to resolve the values directly from the JSON strings
            val inputValue = resolveTemplateValue(criterion.input, actualJson)
            val referenceValue = resolveTemplateValue(criterion.reference, referenceJson)

            logger.debug("String check: comparing '$inputValue' to '$referenceValue' with operation ${criterion.operation}")

            if (inputValue.isBlank()) {
                return CriterionEvaluator.CriterionResult(
                    passed = false,
                    message = "Input value not found or empty: '${criterion.input}' in actual result"
                )
            }

            if (referenceValue.isBlank()) {
                return CriterionEvaluator.CriterionResult(
                    passed = false,
                    message = "Reference value not found or empty: '${criterion.reference}' in reference data"
                )
            }

            // Perform the comparison based on the operation
            val passed = when (criterion.operation) {
                StringCheckGrader.Operation.EQUAL -> inputValue == referenceValue
                StringCheckGrader.Operation.NOT_EQUAL -> inputValue != referenceValue
                StringCheckGrader.Operation.LIKE -> inputValue.contains(referenceValue)
                StringCheckGrader.Operation.ILIKE -> inputValue.lowercase().contains(referenceValue.lowercase())
            }

            return CriterionEvaluator.CriterionResult(
                passed = passed,
                message = if (passed) "Check passed: '$inputValue' ${criterion.operation} '$referenceValue'"
                else "Check failed: '$inputValue' ${criterion.operation} '$referenceValue'"
            )
        } catch (e: Exception) {
            logger.error("Error evaluating string check: ${e.message}", e)
            return CriterionEvaluator.CriterionResult(
                passed = false,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * Resolve a template value using Pebble.
     *
     * @param template The template string (e.g., "{{item.correct_label}}")
     * @param jsonStr The JSON string containing the context
     * @return The resolved value
     */
    private fun resolveTemplateValue(template: String, jsonStr: String): String {
        try {
            // If the JSON is an empty string, return an empty result
            if (jsonStr.isBlank()) {
                return ""
            }
            
            // Parse the JSON into a Map
            val contextMap = mutableMapOf<String, Any>()
            try {
                // Try to parse as JSON object first
                val jsonMap: Map<String, Any> = jacksonObjectMapper().readValue(jsonStr)
                contextMap.putAll(jsonMap)
            } catch (e: Exception) {
                logger.warn("JSON parsing failed, using as plain text: ${e.message}")
                // If JSON parsing fails, treat the entire string as a value
                contextMap["content"] = jsonStr
            }

            // Compile and evaluate the template
            val compiledTemplate = pebbleEngine.getLiteralTemplate(template)
            val writer = StringWriter()
            compiledTemplate.evaluate(writer, contextMap)

            return writer.toString().trim()
        } catch (e: Exception) {
            logger.warn("Error resolving template '$template': ${e.message}")
            return ""
        }
    }
} 
