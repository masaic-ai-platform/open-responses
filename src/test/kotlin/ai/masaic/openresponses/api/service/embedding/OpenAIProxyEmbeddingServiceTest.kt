package ai.masaic.openresponses.api.service.embedding

import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIProxyEmbeddingServiceTest {
    private lateinit var embeddingService: EmbeddingService
    private lateinit var openAIProxyEmbeddingService: OpenAIProxyEmbeddingService

    @BeforeEach
    fun setup() {
        embeddingService = mockk()
        
        // Create the service with mocked dependencies
        openAIProxyEmbeddingService = OpenAIProxyEmbeddingService(embeddingService)
    }

    @Test
    fun `embedText should delegate to EmbeddingService for default model`() {
        // Given
        val inputText = "Test text"
        val expectedEmbedding = listOf(0.1f, 0.2f, 0.3f)
        
        every { embeddingService.embedText(inputText) } returns expectedEmbedding
        
        // When
        val result = openAIProxyEmbeddingService.embedText(inputText, "api-key", "default")
        
        // Then
        assertEquals(expectedEmbedding, result)
        verify(exactly = 1) { embeddingService.embedText(inputText) }
    }

    @Test
    fun `embedTexts should delegate to EmbeddingService for default model`() {
        // Given
        val inputTexts = listOf("First text", "Second text")
        val expectedEmbeddings =
            listOf(
                listOf(0.1f, 0.2f, 0.3f),
                listOf(0.4f, 0.5f, 0.6f),
            )
        
        every { embeddingService.embedTexts(inputTexts) } returns expectedEmbeddings
        
        // When
        val result = openAIProxyEmbeddingService.embedTexts(inputTexts, "api-key", "default")
        
        // Then
        assertEquals(expectedEmbeddings, result)
        verify(exactly = 1) { embeddingService.embedTexts(inputTexts) }
    }

    @Test
    fun `embedText should use OpenAIEmbeddingService for non-default model`() {
        // Given
        val inputText = "Test text"
        val apiKey = "test-api-key"
        val modelName = "openai@text-embedding-3-small"
        val expectedEmbedding = listOf(0.1f, 0.2f, 0.3f)
        
        // Use MockK to intercept the OpenAIEmbeddingService construction and returns
        mockkConstructor(OpenAIEmbeddingService::class)
        
        // Mock the method call on any constructed instance
        every { 
            anyConstructed<OpenAIEmbeddingService>().embedText(inputText) 
        } returns expectedEmbedding
        
        // When
        val result = openAIProxyEmbeddingService.embedText(inputText, apiKey, modelName)
        
        // Then
        assertEquals(expectedEmbedding, result)
        
        // Verify the method call without checking constructor args
        verify { 
            anyConstructed<OpenAIEmbeddingService>().embedText(inputText)
        }
        
        unmockkConstructor(OpenAIEmbeddingService::class)
    }

    @Test
    fun `embedTexts should use OpenAIEmbeddingService for non-default model`() {
        // Given
        val inputTexts = listOf("First text", "Second text")
        val apiKey = "test-api-key"
        val modelName = "openai@text-embedding-3-small"
        val expectedEmbeddings =
            listOf(
                listOf(0.1f, 0.2f, 0.3f),
                listOf(0.4f, 0.5f, 0.6f),
            )
        
        // Use MockK to intercept the OpenAIEmbeddingService construction and returns
        mockkConstructor(OpenAIEmbeddingService::class)
        
        // Mock the method call on any constructed instance
        every { 
            anyConstructed<OpenAIEmbeddingService>().embedTexts(inputTexts) 
        } returns expectedEmbeddings
        
        // When
        val result = openAIProxyEmbeddingService.embedTexts(inputTexts, apiKey, modelName)
        
        // Then
        assertEquals(expectedEmbeddings, result)
        
        // Verify the method call without checking constructor args
        verify { 
            anyConstructed<OpenAIEmbeddingService>().embedTexts(inputTexts)
        }
        
        unmockkConstructor(OpenAIEmbeddingService::class)
    }

    @Test
    fun `modelAndProviderUrl should parse provider and model correctly`() {
        // Test for known provider
        val result1 = invokeModelAndProviderUrl("openai@text-embedding-3-small")
        assertEquals("text-embedding-3-small" to "https://api.openai.com/v1", result1)
        
        // Test for cohere provider
        val result2 = invokeModelAndProviderUrl("cohere@embed-english-v3.0")
        assertEquals("embed-english-v3.0" to "https://api.cohere.ai/compatibility/v1", result2)
        
        // Test for togetherai provider
        val result3 = invokeModelAndProviderUrl("togetherai@togethercomputer/m2-bert-80M-8k-retrieval")
        assertEquals("togethercomputer/m2-bert-80M-8k-retrieval" to "https://api.together.xyz/v1", result3)
    }

    @Test
    fun `modelAndProviderUrl should handle custom URL providers`() {
        // Test for custom URL
        val result = invokeModelAndProviderUrl("http://custom.api.com@custom-model")
        assertEquals("custom-model" to "http://custom.api.com", result)
    }

    @Test
    fun `modelAndProviderUrl should throw for unknown providers`() {
        // When
        val exception = invokeModelAndProviderUrlExpectingException("unknown@model-name")
        
        // Then
        assertTrue(exception is IllegalArgumentException)
        assertEquals("Unknown provider: unknown", exception.message)
    }

    @Test
    fun `modelAndProviderUrl should throw for invalid format`() {
        // When
        val exception = invokeModelAndProviderUrlExpectingException("invalid-format-without-at-symbol")
        
        // Then
        assertTrue(exception is IllegalArgumentException)
        assertEquals("Model name must be in the format 'provider@model'", exception.message)
    }

    /**
     * Helper method to invoke the private modelAndProviderUrl method using reflection
     */
    private fun invokeModelAndProviderUrl(modelName: String): Pair<String, String> {
        val method =
            OpenAIProxyEmbeddingService::class.java.getDeclaredMethod(
                "modelAndProviderUrl", 
                String::class.java,
            )
        method.isAccessible = true
        return method.invoke(openAIProxyEmbeddingService, modelName) as Pair<String, String>
    }

    /**
     * Helper method to invoke the private modelAndProviderUrl method and expect an exception
     */
    private fun invokeModelAndProviderUrlExpectingException(modelName: String): Exception {
        val method =
            OpenAIProxyEmbeddingService::class.java.getDeclaredMethod(
                "modelAndProviderUrl", 
                String::class.java,
            )
        method.isAccessible = true
        try {
            method.invoke(openAIProxyEmbeddingService, modelName)
            throw AssertionError("Expected exception was not thrown")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Unwrap the actual exception thrown by the method
            return e.targetException as Exception
        }
    }
} 
