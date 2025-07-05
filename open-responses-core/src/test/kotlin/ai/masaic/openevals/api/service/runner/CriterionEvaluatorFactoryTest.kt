package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.StringCheckGrader
import ai.masaic.openevals.api.model.TestingCriterion
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CriterionEvaluatorFactoryTest {
    private lateinit var stringCheckEvaluator: CriterionEvaluator
    private lateinit var anotherEvaluator: CriterionEvaluator
    private lateinit var evaluators: List<CriterionEvaluator>
    private lateinit var factory: CriterionEvaluatorFactory

    @BeforeEach
    fun setUp() {
        stringCheckEvaluator = mockk<CriterionEvaluator>()
        anotherEvaluator = mockk<CriterionEvaluator>()
        evaluators = listOf(stringCheckEvaluator, anotherEvaluator)
        factory = CriterionEvaluatorFactory(evaluators)
    }

    @Test
    fun `getEvaluator should return the appropriate evaluator for a criterion`() {
        // Arrange
        val criterion = mockk<StringCheckGrader>()
        every { stringCheckEvaluator.canEvaluate(criterion) } returns true
        every { anotherEvaluator.canEvaluate(criterion) } returns false

        // Act
        val result = factory.getEvaluator(criterion)

        // Assert
        assertSame(stringCheckEvaluator, result)

        // Verify
        verify(exactly = 1) { stringCheckEvaluator.canEvaluate(criterion) }
        verify(exactly = 0) { anotherEvaluator.canEvaluate(criterion) } // Should short-circuit after finding a match
    }

    @Test
    fun `getEvaluator should return the first matching evaluator when multiple can evaluate`() {
        // Arrange
        val criterion = mockk<StringCheckGrader>()
        every { stringCheckEvaluator.canEvaluate(criterion) } returns true
        every { anotherEvaluator.canEvaluate(criterion) } returns true

        // Act
        val result = factory.getEvaluator(criterion)

        // Assert
        assertSame(stringCheckEvaluator, result) // Should return the first one

        // Verify
        verify(exactly = 1) { stringCheckEvaluator.canEvaluate(criterion) }
        verify(exactly = 0) { anotherEvaluator.canEvaluate(criterion) } // Should short-circuit after finding a match
    }

    @Test
    fun `getEvaluator should return null when no evaluator can handle the criterion`() {
        // Arrange
        val criterion = mockk<TestingCriterion>()
        every { stringCheckEvaluator.canEvaluate(criterion) } returns false
        every { anotherEvaluator.canEvaluate(criterion) } returns false

        // Act
        val result = factory.getEvaluator(criterion)

        // Assert
        assertNull(result)

        // Verify
        verify(exactly = 1) { stringCheckEvaluator.canEvaluate(criterion) }
        verify(exactly = 1) { anotherEvaluator.canEvaluate(criterion) }
    }

    @Test
    fun `evaluate should use the appropriate evaluator to evaluate the criterion`() {
        // Arrange
        val criterion = mockk<StringCheckGrader>()
        val actualJson = "{\"actual\": \"value\"}"
        val referenceJson = "{\"reference\": \"value\"}"
        val expectedResult = CriterionEvaluator.CriterionResult(id = "test-1", passed = true, message = "Success")

        every { stringCheckEvaluator.canEvaluate(criterion) } returns true
        every { stringCheckEvaluator.evaluate(criterion, actualJson, referenceJson) } returns expectedResult

        // Act
        val result = factory.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertEquals(expectedResult, result)

        // Verify
        verify(exactly = 1) { stringCheckEvaluator.canEvaluate(criterion) }
        verify(exactly = 1) { stringCheckEvaluator.evaluate(criterion, actualJson, referenceJson) }
    }

    @Test
    fun `evaluate should return a failure result when no evaluator can handle the criterion`() {
        // Arrange
        val criterion = mockk<TestingCriterion>()
        val actualJson = "{\"actual\": \"value\"}"
        val referenceJson = "{\"reference\": \"value\"}"
        
        every { stringCheckEvaluator.canEvaluate(criterion) } returns false
        every { anotherEvaluator.canEvaluate(criterion) } returns false
        every { criterion.id } returns "test-1"

        // Act
        val result = factory.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertFalse(result.passed)
        assertTrue(result.message!!.contains("No evaluator found for criterion type"))

        // Verify
        verify(exactly = 1) { stringCheckEvaluator.canEvaluate(criterion) }
        verify(exactly = 1) { anotherEvaluator.canEvaluate(criterion) }
    }

    @Test
    fun `evaluate should handle empty evaluator list`() {
        // Arrange
        val emptyFactory = CriterionEvaluatorFactory(emptyList())
        val criterion = mockk<TestingCriterion>()
        val actualJson = "{\"actual\": \"value\"}"
        val referenceJson = "{\"reference\": \"value\"}"
        every { criterion.id } returns "test-1"

        // Act
        val result = emptyFactory.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertFalse(result.passed)
        assertTrue(result.message!!.contains("No evaluator found for criterion type"))
    }
} 
