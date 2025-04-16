package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.TestingCriterion

/**
 * Interface for testing criterion evaluators.
 */
interface CriterionEvaluator {
    /**
     * Checks if this evaluator can handle the given testing criterion.
     *
     * @param criterion The criterion to check
     * @return True if this evaluator can handle the criterion
     */
    fun canEvaluate(criterion: TestingCriterion): Boolean

    /**
     * Evaluate the criterion against the actual result and reference data.
     *
     * @param criterion The testing criterion to evaluate
     * @param actualJson The actual result JSON string to evaluate
     * @param referenceJson The reference data JSON string to compare against
     * @return Result of the evaluation
     */
    fun evaluate(
        criterion: TestingCriterion,
        actualJson: String,
        referenceJson: String,
    ): CriterionResult

    /**
     * Data class to store the result of a criterion evaluation.
     */
    data class CriterionResult(
        val id: String,
        val passed: Boolean,
        val message: String? = null,
    )
} 
