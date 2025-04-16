package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.TestingCriterion
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ResultProcessorTest {
    private lateinit var resultProcessor: ResultProcessor

    @BeforeEach
    fun setup() {
        resultProcessor = ResultProcessor()
    }

    @Test
    fun `calculateResultCounts should return correct counts when all items pass`() {
        // Arrange
        val results =
            mapOf(
                0 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true)),
                1 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-2", passed = true)),
                2 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-3", passed = true)),
            )
        
        // Act
        val resultCounts = resultProcessor.calculateResultCounts(results)
        
        // Assert
        assertEquals(3, resultCounts.passed)
        assertEquals(0, resultCounts.failed)
        assertEquals(0, resultCounts.errored)
        assertEquals(3, resultCounts.total)
    }

    @Test
    fun `calculateResultCounts should return correct counts when some items fail`() {
        // Arrange
        val results =
            mapOf(
                0 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true)),
                1 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = false)),
                2 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true)),
            )
        
        // Act
        val resultCounts = resultProcessor.calculateResultCounts(results)
        
        // Assert
        assertEquals(2, resultCounts.passed)
        assertEquals(1, resultCounts.failed)
        assertEquals(0, resultCounts.errored)
        assertEquals(3, resultCounts.total)
    }

    @Test
    fun `calculateResultCounts should return correct counts when some items have errors`() {
        // Arrange
        val results =
            mapOf(
                0 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true)),
                1 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = false, message = "Error: Test error")),
                2 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true)),
            )
        
        // Act
        val resultCounts = resultProcessor.calculateResultCounts(results)
        
        // Assert
        assertEquals(2, resultCounts.passed)
        assertEquals(0, resultCounts.failed)
        assertEquals(1, resultCounts.errored)
        assertEquals(3, resultCounts.total)
    }

    @Test
    fun `calculateResultCounts should handle multiple criteria per item`() {
        // Arrange
        val results =
            mapOf(
                0 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = true),
                    ),
                1 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = false),
                    ),
                2 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = false, message = "Error: Something went wrong"),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = true),
                    ),
            )
        
        // Act
        val resultCounts = resultProcessor.calculateResultCounts(results)
        
        // Assert
        assertEquals(1, resultCounts.passed) // Only the first item passed all criteria
        assertEquals(1, resultCounts.failed) // The second item has one failing criterion but no errors
        assertEquals(1, resultCounts.errored) // The third item has an error
        assertEquals(3, resultCounts.total)
    }

    @Test
    fun `calculateResultCounts should handle empty results`() {
        // Arrange
        val results = emptyMap<Int, Map<String, CriterionEvaluator.CriterionResult>>()
        
        // Act
        val resultCounts = resultProcessor.calculateResultCounts(results)
        
        // Assert
        assertEquals(0, resultCounts.passed)
        assertEquals(0, resultCounts.failed)
        assertEquals(0, resultCounts.errored)
        assertEquals(0, resultCounts.total)
    }

    @Test
    fun `calculatePerCriteriaResults should return correct results for each criterion`() {
        // Arrange
        val criterion1 = TestCriterion("criterion1")
        val criterion2 = TestCriterion("criterion2")
        val testingCriteria = listOf(criterion1, criterion2)
        
        val results =
            mapOf(
                0 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = false),
                    ),
                1 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = false),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = true),
                    ),
                2 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = true),
                    ),
            )
        
        // Act
        val criteriaResults = resultProcessor.calculatePerCriteriaResults(results, testingCriteria)
        
        // Assert
        assertEquals(2, criteriaResults.size)
        
        val criterion1Result = criteriaResults.find { it.testingCriteria == "criterion1" }
        assertEquals(2, criterion1Result?.passed)
        assertEquals(1, criterion1Result?.failed)
        
        val criterion2Result = criteriaResults.find { it.testingCriteria == "criterion2" }
        assertEquals(2, criterion2Result?.passed)
        assertEquals(1, criterion2Result?.failed)
    }

    @Test
    fun `calculatePerCriteriaResults should handle missing criteria in results`() {
        // Arrange
        val criterion1 = TestCriterion("criterion1")
        val criterion2 = TestCriterion("criterion2")
        val criterion3 = TestCriterion("criterion3") // Not present in results
        val testingCriteria = listOf(criterion1, criterion2, criterion3)
        
        val results =
            mapOf(
                0 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true),
                        "criterion2" to CriterionEvaluator.CriterionResult(id = "test-2", passed = false),
                    ),
                1 to
                    mapOf(
                        "criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = false),
                        // criterion2 missing for this item
                    ),
            )
        
        // Act
        val criteriaResults = resultProcessor.calculatePerCriteriaResults(results, testingCriteria)
        
        // Assert
        assertEquals(3, criteriaResults.size)
        
        val criterion1Result = criteriaResults.find { it.testingCriteria == "criterion1" }
        assertEquals(1, criterion1Result?.passed)
        assertEquals(1, criterion1Result?.failed)
        
        val criterion2Result = criteriaResults.find { it.testingCriteria == "criterion2" }
        assertEquals(0, criterion2Result?.passed)
        assertEquals(1, criterion2Result?.failed)
        
        val criterion3Result = criteriaResults.find { it.testingCriteria == "criterion3" }
        assertEquals(0, criterion3Result?.passed)
        assertEquals(0, criterion3Result?.failed)
    }

    @Test
    fun `calculatePerCriteriaResults should handle empty results`() {
        // Arrange
        val criterion1 = TestCriterion("criterion1")
        val criterion2 = TestCriterion("criterion2")
        val testingCriteria = listOf(criterion1, criterion2)
        
        val results = emptyMap<Int, Map<String, CriterionEvaluator.CriterionResult>>()
        
        // Act
        val criteriaResults = resultProcessor.calculatePerCriteriaResults(results, testingCriteria)
        
        // Assert
        assertEquals(2, criteriaResults.size)
        
        val criterion1Result = criteriaResults.find { it.testingCriteria == "criterion1" }
        assertEquals(0, criterion1Result?.passed)
        assertEquals(0, criterion1Result?.failed)
        
        val criterion2Result = criteriaResults.find { it.testingCriteria == "criterion2" }
        assertEquals(0, criterion2Result?.passed)
        assertEquals(0, criterion2Result?.failed)
    }

    @Test
    fun `calculatePerCriteriaResults should handle empty testing criteria`() {
        // Arrange
        val testingCriteria = emptyList<TestingCriterion>()
        
        val results =
            mapOf(
                0 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-1", passed = true)),
                1 to mapOf("criterion1" to CriterionEvaluator.CriterionResult(id = "test-2", passed = false)),
            )
        
        // Act
        val criteriaResults = resultProcessor.calculatePerCriteriaResults(results, testingCriteria)
        
        // Assert
        assertEquals(0, criteriaResults.size)
    }

    // Helper class for tests
    private data class TestCriterion(
        override val name: String,
        override val id: String = "",
    ) : TestingCriterion
}
