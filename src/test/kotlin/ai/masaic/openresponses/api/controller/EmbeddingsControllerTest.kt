package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.CreateEmbeddingRequest
import ai.masaic.openresponses.api.service.embedding.OpenAIProxyEmbeddingService
import ai.masaic.openresponses.api.support.service.TelemetryService
import com.knuddels.jtokkit.api.Encoding
import io.micrometer.observation.Observation
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddingsControllerTest {
    private lateinit var embeddingService: OpenAIProxyEmbeddingService
    private lateinit var telemetryService: TelemetryService
    private lateinit var encoding: Encoding
    private lateinit var controller: EmbeddingsController
    private lateinit var observationMock: Observation

    @BeforeEach
    fun setup() {
        embeddingService = mockk()
        telemetryService = mockk()
        encoding = mockk()
        observationMock = mockk(relaxed = true)

        coEvery { telemetryService.startObservation(any(), "openai@text-embedding-3-small") } returns observationMock
        every { observationMock.lowCardinalityKeyValue(any(), any()) } returns observationMock
        every { observationMock.error(any<Exception>()) } returns observationMock
        every { observationMock.stop() } returns Unit

        // Mock the token counting functionality
        every { encoding.countTokens(any<String>()) } returns 10

        controller = EmbeddingsController(embeddingService, encoding, telemetryService)
    }

    @Test
    fun `createEmbedding should return embedding response with float format`() =
        runBlocking {
            // Given
            val authHeader = "Bearer test-api-key"
            val request =
                CreateEmbeddingRequest(
                    input = "Test text",
                    model = "openai@text-embedding-3-small",
                    encodingFormat = "float",
                )
            val embeddings = listOf(listOf(0.1f, 0.2f, 0.3f))
        
            every { 
                embeddingService.embedTexts(listOf("Test text"), "test-api-key", "openai@text-embedding-3-small") 
            } returns embeddings
        
            every { embeddingService.providers } returns
                mapOf(
                    "openai" to "https://api.openai.com/v1",
                )

            // When
            val response = controller.createEmbedding(request, authHeader)

            // Then
            assertEquals(HttpStatus.OK, response.statusCode)
            val responseBody = response.body!!
            assertEquals("openai@text-embedding-3-small", responseBody.model)
            assertEquals(1, responseBody.data.size)
            assertEquals(0, responseBody.data[0].index)
            assertTrue(responseBody.data[0].embedding is List<*>)
            assertEquals(embeddings[0], responseBody.data[0].embedding)
            assertEquals(10, responseBody.usage.promptTokens)
            assertEquals(10, responseBody.usage.totalTokens)

            // Verify telemetry
            coVerify { telemetryService.startObservation("gen_ai.embeddings", "openai@text-embedding-3-small") }
            verify { observationMock.lowCardinalityKeyValue("gen_ai.operation.name", "embeddings") }
            verify { observationMock.lowCardinalityKeyValue("gen_ai.request.model", "openai@text-embedding-3-small") }
            verify { observationMock.lowCardinalityKeyValue("server.address", "https://api.openai.com/v1") }
            verify { observationMock.stop() }
        }

    @Test
    fun `createEmbedding should return embedding response with base64 format`() =
        runBlocking {
            // Given
            val authHeader = "Bearer test-api-key"
            val request =
                CreateEmbeddingRequest(
                    input = "Test text",
                    model = "openai@text-embedding-3-small",
                    encodingFormat = "base64",
                )
            val embeddings = listOf(listOf(0.1f, 0.2f, 0.3f))
        
            every { 
                embeddingService.embedTexts(listOf("Test text"), "test-api-key", "openai@text-embedding-3-small") 
            } returns embeddings
        
            every { embeddingService.providers } returns
                mapOf(
                    "openai" to "https://api.openai.com/v1",
                )

            // When
            val response = controller.createEmbedding(request, authHeader)

            // Then
            assertEquals(HttpStatus.OK, response.statusCode)
            val responseBody = response.body!!
            assertEquals("openai@text-embedding-3-small", responseBody.model)
            assertEquals(1, responseBody.data.size)
            assertEquals(0, responseBody.data[0].index)
            assertTrue(responseBody.data[0].embedding is String) // Base64 encoded
        
            // Verify telemetry
            coVerify { telemetryService.startObservation("gen_ai.embeddings", "openai@text-embedding-3-small") }
            verify { observationMock.lowCardinalityKeyValue("gen_ai.request.encoding_formats", "base64") }
            verify { observationMock.stop() }
        }

    @Test
    fun `createEmbedding should handle list input`() =
        runBlocking {
            // Given
            val authHeader = "Bearer test-api-key"
            val inputList = listOf("Text 1", "Text 2")
            val request =
                CreateEmbeddingRequest(
                    input = inputList,
                    model = "openai@text-embedding-3-small",
                    encodingFormat = "float",
                )
            val embeddings =
                listOf(
                    listOf(0.1f, 0.2f, 0.3f),
                    listOf(0.4f, 0.5f, 0.6f),
                )
        
            every { 
                embeddingService.embedTexts(inputList, "test-api-key", "openai@text-embedding-3-small") 
            } returns embeddings
        
            every { embeddingService.providers } returns
                mapOf(
                    "openai" to "https://api.openai.com/v1",
                )

            // When
            val response = controller.createEmbedding(request, authHeader)

            // Then
            assertEquals(HttpStatus.OK, response.statusCode)
            val responseBody = response.body!!
            assertEquals(2, responseBody.data.size)
            assertEquals(0, responseBody.data[0].index)
            assertEquals(1, responseBody.data[1].index)
            assertEquals(embeddings[0], responseBody.data[0].embedding)
            assertEquals(embeddings[1], responseBody.data[1].embedding)
        }

    @Test
    fun `createEmbedding should handle invalid API key`() =
        runBlocking {
            // Given
            val authHeader = "Bearer " // Empty API key
            val request =
                CreateEmbeddingRequest(
                    input = "Test text",
                    model = "openai@text-embedding-3-small",
                )

            // When/Then
            val exception =
                assertFailsWith<ResponseStatusException> {
                    controller.createEmbedding(request, authHeader)
                }
        
            assertEquals(HttpStatus.UNAUTHORIZED, exception.statusCode)
            assertEquals("Invalid API key", exception.reason)
        
            verify { observationMock.lowCardinalityKeyValue("error.type", "authentication_error") }
            verify { observationMock.error(any<ResponseStatusException>()) }
            verify { observationMock.stop() }
        }

    @Test
    fun `createEmbedding should reject invalid encoding format`() =
        runBlocking {
            // Given
            val authHeader = "Bearer test-api-key"
            val request =
                CreateEmbeddingRequest(
                    input = "Test text",
                    model = "openai@text-embedding-3-small",
                    encodingFormat = "invalid",
                )

            // When/Then
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    controller.createEmbedding(request, authHeader)
                }
        
            assertTrue(exception.message?.contains("encoding_format must be either 'float' or 'base64'") == true)
        
            verify { observationMock.lowCardinalityKeyValue("error.type", "invalid_encoding_format") }
            verify { observationMock.error(any<IllegalArgumentException>()) }
            verify { observationMock.stop() }
        }

    @Test
    fun `createEmbedding should handle invalid input type`() =
        runBlocking {
            // Given
            val authHeader = "Bearer test-api-key"
            val request =
                CreateEmbeddingRequest(
                    input = 123, // Invalid input type
                    model = "openai@text-embedding-3-small",
                )

            // When/Then
            val exception =
                assertFailsWith<IllegalArgumentException> {
                    controller.createEmbedding(request, authHeader)
                }
        
            assertEquals("Input must be a string or an array of strings", exception.message)
        
            verify { observationMock.stop() }
        }

    @Test
    fun `createEmbedding should handle service exceptions`() =
        runBlocking {
            // Given
            val authHeader = "Bearer test-api-key"
            val request =
                CreateEmbeddingRequest(
                    input = "Test text",
                    model = "openai@text-embedding-3-small",
                )
        
            every { 
                embeddingService.embedTexts(any(), any(), any()) 
            } throws RuntimeException("Service error")
        
            every { embeddingService.providers } returns
                mapOf(
                    "openai" to "https://api.openai.com/v1",
                )

            // When/Then
            val exception =
                assertFailsWith<RuntimeException> {
                    controller.createEmbedding(request, authHeader)
                }
            assertEquals("Service error", exception.message)

            verify { observationMock.error(any<RuntimeException>()) }
            verify { observationMock.lowCardinalityKeyValue("error.type", "RuntimeException") }
            verify { observationMock.stop() }
        }
} 
