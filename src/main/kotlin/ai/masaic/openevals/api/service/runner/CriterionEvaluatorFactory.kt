package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.TestingCriterion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Factory class for managing criterion evaluators.
 */
@Component
class CriterionEvaluatorFactory(
    private val evaluators: List<CriterionEvaluator>,
) {
    private val logger = LoggerFactory.getLogger(CriterionEvaluatorFactory::class.java)

    init {
        logger.info("loaded evaluators: $evaluators")
    }

    /**
     * Get the appropriate evaluator for the given criterion.
     *
     * @param criterion The testing criterion
     * @return The appropriate evaluator or null if none found
     */
    fun getEvaluator(criterion: TestingCriterion): CriterionEvaluator? {
        val evaluator = evaluators.find { it.canEvaluate(criterion) }
        
        if (evaluator == null) {
            logger.warn("No evaluator found for criterion type: ${criterion.javaClass.simpleName}")
        }
        
        return evaluator
    }

    /**
     * Evaluate a criterion using the appropriate evaluator.
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
    ): CriterionEvaluator.CriterionResult {
        val evaluator = getEvaluator(criterion)
        
        return evaluator?.evaluate(criterion, actualJson, referenceJson)
            ?: CriterionEvaluator.CriterionResult(
                id = criterion.id,
                passed = false,
                message = "No evaluator found for criterion type: ${criterion.javaClass.simpleName}",
            )
    }
} 
