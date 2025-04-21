package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.CompletionResult
import ai.masaic.openevals.api.model.LabelModelGrader
import ai.masaic.openevals.api.model.SimpleInputMessage
import ai.masaic.openevals.api.model.TestingCriterion
import ai.masaic.openevals.api.service.ModelClientService
import ai.masaic.openevals.api.utils.TemplateUtils
import com.mitchellbosecke.pebble.PebbleEngine
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class LabelModelGraderEvaluatorTest {
    private lateinit var evaluator: LabelModelGraderEvaluator
    private lateinit var mockPebbleEngine: PebbleEngine
    private lateinit var mockModelClientService: ModelClientService

    @BeforeEach
    fun setUp() {
        mockPebbleEngine = mockk<PebbleEngine>()
        mockModelClientService = mockk<ModelClientService>()
        evaluator = LabelModelGraderEvaluator(mockPebbleEngine, mockModelClientService)
    }

    @Test
    fun `canEvaluate should return true for LabelModelGrader`() {
        // Arrange
        val criterion = mockk<LabelModelGrader>()
        
        // Act
        val result = evaluator.canEvaluate(criterion)
        
        // Assert
        assertTrue(result)
    }

    @Test
    fun `canEvaluate should return false for non-LabelModelGrader`() {
        // Arrange
        val criterion = mockk<TestingCriterion>()
        
        // Act
        val result = evaluator.canEvaluate(criterion)
        
        // Assert
        assertFalse(result)
    }

    @Test
    fun `evaluate should return error result when criterion is not LabelModelGrader`() {
        // Arrange
        val criterion = mockk<TestingCriterion>()
        every { criterion.id } returns "test-id"
        
        // Act
        val result = evaluator.evaluate(criterion, "{}", "{}")
        
        // Assert
        assertEquals("test-id", result.id)
        assertFalse(result.passed)
        assertTrue(result.message!!.contains("Invalid criterion type"))
    }

    @Test
    fun `evaluate should return passed result when model returns matching label`() {
        // Arrange
        val inputMessages =
            listOf(
                SimpleInputMessage("user", "Test content with {{ data.value }}"),
                SimpleInputMessage("system", "System message"),
            )
        
        val criterion = mockk<LabelModelGrader>()
        every { criterion.id } returns "test-id"
        every { criterion.input } returns inputMessages
        every { criterion.model } returns "gpt-4"
        every { criterion.apiKey } returns "test-api-key"
        every { criterion.labels } returns listOf("positive", "negative", "neutral")
        every { criterion.passingLabels } returns listOf("positive", "neutral")
        
        // Mock TemplateUtils using the same approach as StringCheckEvaluatorTest
        mockkObject(TemplateUtils)
        every { 
            TemplateUtils.resolveTemplateValue("Test content with {{ data.value }}", """{"data": {"value": "actual value"}}""", mockPebbleEngine) 
        } returns "Test content with actual value"
        
        every { 
            TemplateUtils.resolveTemplateValue("System message", """{"data": {"value": "actual value"}}""", mockPebbleEngine) 
        } returns "System message"
        
        // Mock builder and client service
        val mockBuilder = mockk<ChatCompletionCreateParams.Builder>(relaxed = true)
        every { mockModelClientService.createBasicCompletionParams(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockk()
        
        // Mock the completion result - return "positive" label
        every {
            mockModelClientService.executeWithClientAndErrorHandling(
                apiKey = any(),
                params = any(),
                identifier = any(),
                resultBuilder = any(),
            )
        } answers {
            val resultBuilder = lastArg<(String, String?) -> CompletionResult>()
            resultBuilder("""{"item":{"label":"positive"},"sample":{"model":"chat"}}""", null)
        }
        
        // Act
        val result = evaluator.evaluate(criterion, """{"data": {"value": "actual value"}}""", "{}")
        
        // Assert
        assertEquals("test-id", result.id)
        assertTrue(result.passed)
        assertTrue(result.message!!.contains("positive"))
        
        // Verify and clean up
        verify(exactly = 1) { 
            TemplateUtils.resolveTemplateValue("Test content with {{ data.value }}", """{"data": {"value": "actual value"}}""", mockPebbleEngine) 
        }
        verify(exactly = 1) { 
            TemplateUtils.resolveTemplateValue("System message", """{"data": {"value": "actual value"}}""", mockPebbleEngine) 
        }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should return failed result when model returns non-matching label`() {
        // Arrange
        val inputMessages =
            listOf(
                SimpleInputMessage("user", "Test content"),
            )
        
        val criterion = mockk<LabelModelGrader>()
        every { criterion.id } returns "test-id"
        every { criterion.input } returns inputMessages
        every { criterion.model } returns "gpt-4"
        every { criterion.apiKey } returns "test-api-key"
        every { criterion.labels } returns listOf("positive", "negative", "neutral")
        every { criterion.passingLabels } returns listOf("positive")
        
        // Mock TemplateUtils
        mockkObject(TemplateUtils)
        every { 
            TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) 
        } returns "Test content"
        
        // Mock builder and client service
        val mockBuilder = mockk<ChatCompletionCreateParams.Builder>(relaxed = true)
        every { mockModelClientService.createBasicCompletionParams(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockk()
        
        // Mock the completion result - return "negative" label (not in passing labels)
        every {
            mockModelClientService.executeWithClientAndErrorHandling(
                apiKey = any(),
                params = any(),
                identifier = any(),
                resultBuilder = any(),
            )
        } answers {
            val resultBuilder = lastArg<(String, String?) -> CompletionResult>()
            resultBuilder("""{"item":{"label":"negative"},"sample":{"model":"chat"}}""", null)
        }
        
        // Act
        val result = evaluator.evaluate(criterion, "{}", "{}")
        
        // Assert
        assertEquals("test-id", result.id)
        assertFalse(result.passed)
        assertTrue(result.message!!.contains("label check failed", ignoreCase = true))
        assertTrue(result.message!!.contains("negative"))
        
        // Verify and clean up
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should handle API errors properly`() {
        // Arrange
        val inputMessages =
            listOf(
                SimpleInputMessage("user", "Test content"),
            )
        
        val criterion = mockk<LabelModelGrader>()
        every { criterion.id } returns "test-id"
        every { criterion.input } returns inputMessages
        every { criterion.model } returns "gpt-4"
        every { criterion.apiKey } returns "test-api-key"
        every { criterion.labels } returns listOf("positive", "negative", "neutral")
        
        // Mock TemplateUtils
        mockkObject(TemplateUtils)
        every { 
            TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) 
        } returns "Test content"
        
        // Mock builder and client service
        val mockBuilder = mockk<ChatCompletionCreateParams.Builder>(relaxed = true)
        every { mockModelClientService.createBasicCompletionParams(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockk()
        
        // Mock the completion result - return an error
        every {
            mockModelClientService.executeWithClientAndErrorHandling(
                apiKey = any(),
                params = any(),
                identifier = any(),
                resultBuilder = any(),
            )
        } answers {
            val resultBuilder = lastArg<(String, String?) -> CompletionResult>()
            resultBuilder("", "API Error")
        }
        
        // Act
        val result = evaluator.evaluate(criterion, "{}", "{}")
        
        // Assert
        assertEquals("test-id", result.id)
        assertFalse(result.passed)
        assertTrue(result.message!!.contains("Error"))
        
        // Verify and clean up
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should find closest matching label when exact match not found`() {
        // Arrange
        val inputMessages =
            listOf(
                SimpleInputMessage("user", "Test content"),
            )
        
        val criterion = mockk<LabelModelGrader>()
        every { criterion.id } returns "test-id"
        every { criterion.input } returns inputMessages
        every { criterion.model } returns "gpt-4"
        every { criterion.apiKey } returns "test-api-key"
        every { criterion.labels } returns listOf("positive", "negative", "neutral")
        every { criterion.passingLabels } returns listOf("positive")
        
        // Mock TemplateUtils
        mockkObject(TemplateUtils)
        every { 
            TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) 
        } returns "Test content"
        
        // Mock builder and client service
        val mockBuilder = mockk<ChatCompletionCreateParams.Builder>(relaxed = true)
        every { mockModelClientService.createBasicCompletionParams(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockk()
        
        // Mock the completion result - return text that contains but doesn't match "positive"
        every {
            mockModelClientService.executeWithClientAndErrorHandling(
                apiKey = any(),
                params = any(),
                identifier = any(),
                resultBuilder = any(),
            )
        } answers {
            val resultBuilder = lastArg<(String, String?) -> CompletionResult>()
            resultBuilder("""{"item":{"label":"The sentiment is positive."},"sample":{"model":"chat"}}""", null)
        }
        
        // Act
        val result = evaluator.evaluate(criterion, "{}", "{}")
        
        // Assert
        assertEquals("test-id", result.id)
        assertTrue(result.passed)
        assertTrue(result.message!!.contains("positive"))
        
        // Verify and clean up
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) }
        unmockkObject(TemplateUtils)
    }

    @Test
    fun `evaluate should use first label as fallback when no match found`() {
        // Arrange
        val inputMessages =
            listOf(
                SimpleInputMessage("user", "Test content"),
            )
        
        val criterion = mockk<LabelModelGrader>()
        every { criterion.id } returns "test-id"
        every { criterion.input } returns inputMessages
        every { criterion.model } returns "gpt-4"
        every { criterion.apiKey } returns "test-api-key"
        every { criterion.labels } returns listOf("positive", "negative", "neutral")
        every { criterion.passingLabels } returns listOf("negative")
        
        // Mock TemplateUtils
        mockkObject(TemplateUtils)
        every { 
            TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) 
        } returns "Test content"
        
        // Mock builder and client service
        val mockBuilder = mockk<ChatCompletionCreateParams.Builder>(relaxed = true)
        every { mockModelClientService.createBasicCompletionParams(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockk()
        
        // Mock the completion result - return text that doesn't match any label
        every {
            mockModelClientService.executeWithClientAndErrorHandling(
                apiKey = any(),
                params = any(),
                identifier = any(),
                resultBuilder = any(),
            )
        } answers {
            val resultBuilder = lastArg<(String, String?) -> CompletionResult>()
            resultBuilder("""{"item":{"label":"Something completely different"},"sample":{"model":"chat"}}""", null)
        }
        
        // Act
        val result = evaluator.evaluate(criterion, "{}", "{}")
        
        // Assert
        assertEquals("test-id", result.id)
        assertFalse(result.passed)
        assertTrue(result.message!!.contains("positive"))
        assertTrue(result.message!!.contains("Something completely different"))
        
        // Verify and clean up
        verify(exactly = 1) { TemplateUtils.resolveTemplateValue("Test content", "{}", mockPebbleEngine) }
        unmockkObject(TemplateUtils)
    }
} 
