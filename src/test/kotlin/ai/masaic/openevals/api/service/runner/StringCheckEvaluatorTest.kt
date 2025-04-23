package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.StringCheckGrader
import ai.masaic.openevals.api.model.TestingCriterion
import ai.masaic.openevals.api.utils.TemplateUtils
import com.mitchellbosecke.pebble.PebbleEngine
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class StringCheckEvaluatorTest {
    private lateinit var pebbleEngine: PebbleEngine
    private lateinit var evaluator: StringCheckEvaluator

    @BeforeEach
    fun setUp() {
        pebbleEngine = mockk<PebbleEngine>()
        evaluator = StringCheckEvaluator(pebbleEngine)
    }

    @Test
    fun `canEvaluate should return true for StringCheckGrader criterion`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Test String Check",
                id = "test-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.EQUAL,
            )

        // Act
        val result = evaluator.canEvaluate(criterion)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `canEvaluate should return false for non-StringCheckGrader criterion`() {
        // Arrange
        val criterion = mockk<TestingCriterion>()

        // Act
        val result = evaluator.canEvaluate(criterion)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `evaluate should return false result for non-StringCheckGrader criterion`() {
        // Arrange
        val criterion = mockk<TestingCriterion>()
        every { criterion.id } returns "test-1"
        // Act
        val result = evaluator.evaluate(criterion, "{}", "{}")

        // Assert
        assertFalse(result.passed)
        assertTrue(result.message!!.startsWith("Invalid criterion type: expected StringCheckGrader but got"))
    }

    @Test
    fun `evaluate should return passed result for EQUAL operation with matching values`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Equal Check",
                id = "equal-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.EQUAL,
            )

        val actualJson = """{"response": {"answer": "test value"}}"""
        val referenceJson = """{"expected": {"answer": "test value"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } returns "test value"
        every { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) } returns "test value"

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertTrue(result.passed)
        assertEquals("Check passed: 'test value' EQUAL 'test value'", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return failed result for EQUAL operation with non-matching values`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Equal Check",
                id = "equal-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.EQUAL,
            )

        val actualJson = """{"response": {"answer": "test value"}}"""
        val referenceJson = """{"expected": {"answer": "different value"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } returns "test value"
        every { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) } returns "different value"

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertFalse(result.passed)
        assertEquals("Check failed: 'test value' EQUAL 'different value'", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return passed result for NOT_EQUAL operation with different values`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Not Equal Check",
                id = "not-equal-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.NOT_EQUAL,
            )

        val actualJson = """{"response": {"answer": "test value"}}"""
        val referenceJson = """{"expected": {"answer": "different value"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } returns "test value"
        every { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) } returns "different value"

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertTrue(result.passed)
        assertEquals("Check passed: 'test value' NOT_EQUAL 'different value'", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return passed result for LIKE operation with substring match`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Like Check",
                id = "like-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.LIKE,
            )

        val actualJson = """{"response": {"answer": "this is a test value"}}"""
        val referenceJson = """{"expected": {"answer": "test"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } returns "this is a test value"
        every { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) } returns "test"

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertTrue(result.passed)
        assertEquals("Check passed: 'this is a test value' LIKE 'test'", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return passed result for ILIKE operation with case-insensitive match`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "ILike Check",
                id = "ilike-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.ILIKE,
            )

        val actualJson = """{"response": {"answer": "This is a TEST value"}}"""
        val referenceJson = """{"expected": {"answer": "test"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } returns "This is a TEST value"
        every { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) } returns "test"

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertTrue(result.passed)
        assertEquals("Check passed: 'This is a TEST value' ILIKE 'test'", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return failed result when input value is empty`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Equal Check",
                id = "equal-id",
                input = "{{response.missing}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.EQUAL,
            )

        val actualJson = """{"response": {"answer": "test value"}}"""
        val referenceJson = """{"expected": {"answer": "test value"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.missing}}", actualJson, pebbleEngine) } returns ""
        every { TemplateUtils.resolveTemplateValue("{{expected.answer}}", referenceJson, pebbleEngine) } returns "test value"

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertFalse(result.passed)
        assertEquals("Input value not found or empty: '{{response.missing}}' in actual result", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.missing}}", actualJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return failed result when reference value is empty`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Equal Check",
                id = "equal-id",
                input = "{{response.answer}}",
                reference = "{{expected.missing}}",
                operation = StringCheckGrader.Operation.EQUAL,
            )

        val actualJson = """{"response": {"answer": "test value"}}"""
        val referenceJson = """{"expected": {"answer": "test value"}}"""

        // Mock TemplateUtils to return the expected values
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } returns "test value"
        every { TemplateUtils.resolveTemplateValue("{{expected.missing}}", referenceJson, pebbleEngine) } returns ""

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertFalse(result.passed)
        assertEquals("Reference value not found or empty: '{{expected.missing}}' in reference data", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{expected.missing}}", referenceJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return failed result when TemplateUtils throws exception`() {
        // Arrange
        val criterion =
            StringCheckGrader(
                name = "Equal Check",
                id = "equal-id",
                input = "{{response.answer}}",
                reference = "{{expected.answer}}",
                operation = StringCheckGrader.Operation.EQUAL,
            )

        val actualJson = """{"response": {"answer": "test value"}}"""
        val referenceJson = """{"expected": {"answer": "test value"}}"""

        // Mock TemplateUtils to throw an exception
        mockkObject(TemplateUtils)
        every { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) } throws RuntimeException("Template error")

        // Act
        val result = evaluator.evaluate(criterion, actualJson, referenceJson)

        // Assert
        assertFalse(result.passed)
        assertEquals("Error: Template error", result.message)

        // Verify
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("{{response.answer}}", actualJson, pebbleEngine) }
        unmockkObject(TemplateUtils)
    }
} 
