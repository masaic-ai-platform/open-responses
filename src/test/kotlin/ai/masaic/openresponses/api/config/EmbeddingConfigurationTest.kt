package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.service.DefaultEmbeddingService
import ai.masaic.openresponses.api.service.OpenAIEmbeddingService
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel
import dev.langchain4j.model.embedding.onnx.PoolingMode
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbeddingConfigurationTest {
    private val configuration = EmbeddingConfiguration()

    @Test
    fun `embeddingModel should create AllMiniLmL6V2EmbeddingModel by default`() {
        // Given
        val properties = EmbeddingProperties()

        // When
        val model = configuration.embeddingModel(properties)

        // Then
        assertTrue(model is AllMiniLmL6V2EmbeddingModel)
    }

    @Test
    fun `embeddingModel should throw exception for missing ONNX paths`() {
        // Given
        val properties = EmbeddingProperties(
            modelType = "onnx"
        )
        // Then
        assertThrows<IllegalArgumentException> {
            configuration.embeddingModel(properties)
        }
    }

    @Test
    fun `embeddingModel should throw exception for unsupported model type`() {
        // Given
        val properties = EmbeddingProperties(
            modelType = "unsupported-model"
        )

        // Then
        assertThrows<IllegalArgumentException> {
            configuration.embeddingModel(properties)
        }
    }

    @Test
    fun `defaultEmbeddingService should create service with provided model`() {
        // Given
        val mockModel = AllMiniLmL6V2EmbeddingModel()

        // When
        val service = configuration.defaultEmbeddingService(mockModel)

        // Then
        assertTrue(service is DefaultEmbeddingService)
    }

    @Test
    fun `openAIEmbeddingService should create service with provided properties`() {
        // Given
        val properties = EmbeddingProperties(
            apiKey = "test-api-key",
            model = "text-embedding-3-small",
            url = "https://api.openai.com/v1"
        )

        // When
        val service = configuration.openAIEmbeddingService(properties)

        // Then
        assertTrue(service is OpenAIEmbeddingService)
    }

    @Test
    fun `EmbeddingProperties should have correct default values`() {
        // Given
        val properties = EmbeddingProperties()

        // Then
        assertEquals("all-minilm-l6-v2", properties.modelType)
        assertEquals(null, properties.onnxModelPath)
        assertEquals(null, properties.tokenizerPath)
        assertEquals("mean", properties.poolingMode)
        assertEquals("", properties.apiKey)
        assertEquals(false, properties.httpEnabled)
        assertEquals("", properties.model)
        assertEquals("", properties.url)
    }

    @Test
    fun `embeddingService should create a service with the provided model`() {
        // Given
        val embeddingModel = mockk<EmbeddingModel>()
        
        // When
        val service = configuration.defaultEmbeddingService(embeddingModel)
        
        // Then
        assertNotNull(service, "Embedding service should be created")
    }
} 
