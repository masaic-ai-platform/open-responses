package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.ResultCounts
import ai.masaic.openevals.api.model.TestingCriteriaResult
import ai.masaic.openevals.api.model.TestingCriterion
import org.springframework.stereotype.Component

/**
 * Component for processing and calculating evaluation results.
 */
@Component
class ResultProcessor {
    /**
     * Calculate overall result counts from testing criteria results.
     *
     * @param results Map of testing criteria results
     * @return Overall result counts
     */
    fun calculateResultCounts(
        results: Map<Int, Map<String, CriterionEvaluator.CriterionResult>>,
    ): ResultCounts {
        var passed = 0
        var failed = 0
        var errored = 0

        results.forEach { (_, criteriaResults) ->
            // If all criteria for this item passed, count the item as passed
            val allPassed = criteriaResults.values.all { it.passed }
            val anyError = criteriaResults.values.any { it.message?.startsWith("Error:") == true }

            when {
                anyError -> errored++
                allPassed -> passed++
                else -> failed++
            }
        }

        return ResultCounts(
            passed = passed,
            failed = failed,
            errored = errored,
            total = results.size,
        )
    }

    /**
     * Calculate per-criteria results from testing criteria results.
     *
     * @param results Map of testing criteria results
     * @param testingCriteria List of testing criteria
     * @return List of per-criteria results
     */
    fun calculatePerCriteriaResults(
        results: Map<Int, Map<String, CriterionEvaluator.CriterionResult>>,
        testingCriteria: List<TestingCriterion>,
    ): List<TestingCriteriaResult> =
        testingCriteria.map { criterion ->
            var passed = 0
            var failed = 0

            val criterionResultList = mutableListOf<CriterionEvaluator.CriterionResult>()
            results.forEach { (_, criteriaResults) ->
                criteriaResults[criterion.name]?.let { result ->
                    if (result.passed) passed++ else failed++
                    criterionResultList.add(result)
                }
            }

            TestingCriteriaResult(
                testingCriteria = criterion.name,
                criterionResults = criterionResultList,
                passed = passed,
                failed = failed,
            )
        }
} 
