package ai.masaic.openresponses.api.config

import dev.langchain4j.model.embedding.EmbeddingModel
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class EmbeddingConfigurationTest {
    @Test
    fun `embeddingModel should create a model based on properties`() {
        // Given
        val configuration = EmbeddingConfiguration()
        val properties = EmbeddingProperties(modelType = "all-minilm-l6-v2")
        
        // When
        val model = configuration.embeddingModel(properties)
        
        // Then
        assertNotNull(model, "Embedding model should be created")
    }

    @Test
    fun `embeddingService should create a service with the provided model`() {
        // Given
        val configuration = EmbeddingConfiguration()
        val embeddingModel = mockk<EmbeddingModel>()
        
        // When
        val service = configuration.embeddingService(embeddingModel)
        
        // Then
        assertNotNull(service, "Embedding service should be created")
    }
} 
