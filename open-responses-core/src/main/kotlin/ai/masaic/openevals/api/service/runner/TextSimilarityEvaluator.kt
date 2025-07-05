package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.TestingCriterion
import ai.masaic.openevals.api.model.TextSimilarityGrader
import ai.masaic.openevals.api.utils.TemplateUtils
import com.mitchellbosecke.pebble.PebbleEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Implementation of CriterionEvaluator for text similarity testing criteria.
 */
@Component
class TextSimilarityEvaluator(
    private val pebbleEngine: PebbleEngine,
) : CriterionEvaluator {
    private val logger = LoggerFactory.getLogger(TextSimilarityEvaluator::class.java)

    /**
     * Checks if this evaluator can handle the given testing criterion.
     *
     * @param criterion The criterion to check
     * @return True if this evaluator can handle the criterion
     */
    override fun canEvaluate(criterion: TestingCriterion): Boolean = criterion is TextSimilarityGrader

    /**
     * Evaluate the text similarity criterion against the actual result and reference data.
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
        if (criterion !is TextSimilarityGrader) {
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = false,
                message = "Invalid criterion type: expected TextSimilarityGrader but got ${criterion.javaClass.simpleName}",
            )
        }
        
        try {
            // Use Pebble to resolve the values directly from the JSON strings
            val inputValue = TemplateUtils.resolveTemplateValue(criterion.input, actualJson, pebbleEngine)
            val referenceValue = TemplateUtils.resolveTemplateValue(criterion.reference, referenceJson, pebbleEngine)

            if (inputValue.isBlank()) {
                return CriterionEvaluator.CriterionResult(
                    id = criterion.id,
                    passed = false,
                    message = "Input value not found or empty: '${criterion.input}' in actual result",
                )
            }

            if (referenceValue.isBlank()) {
                return CriterionEvaluator.CriterionResult(
                    id = criterion.id,
                    passed = false,
                    message = "Reference value not found or empty: '${criterion.reference}' in reference data",
                )
            }

            // Calculate similarity
            val similarity = calculateSimilarity(inputValue, referenceValue)
            val passed = similarity >= criterion.passThreshold

            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = passed,
                message =
                    if (passed) {
                        "Similarity check passed: '$inputValue' has similarity score $similarity with '$referenceValue' (threshold: ${criterion.passThreshold})"
                    } else {
                        "Similarity check failed: '$inputValue' has similarity score $similarity with '$referenceValue' (threshold: ${criterion.passThreshold})"
                    },
            )
        } catch (e: Exception) {
            logger.error("Error evaluating text similarity: ${e.message}", e)
            return CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = false,
                message = "Error: ${e.message}",
            )
        }
    }

    /**
     * Calculate a simple similarity score between two strings.
     * This is a placeholder for more sophisticated similarity measures.
     *
     * @param str1 First string
     * @param str2 Second string
     * @return Similarity score between 0.0 and 1.0
     */
    private fun calculateSimilarity(
        str1: String,
        str2: String,
    ): Double {
        // Simple implementation using Jaccard similarity of words
        val words1 = str1.lowercase().split(Regex("\\W+")).toSet()
        val words2 = str2.lowercase().split(Regex("\\W+")).toSet()

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
} 
