package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.embedding.OnnxEmbeddingService
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EmbeddingServiceTest {
    private lateinit var embeddingModel: EmbeddingModel
    private lateinit var embeddingService: EmbeddingService

    @BeforeEach
    fun setup() {
        // Create a mock EmbeddingModel
        embeddingModel = mockk<EmbeddingModel>()
        
        // Create sample embeddings to return
        val sampleEmbedding = createSampleEmbedding(listOf(0.1f, 0.2f, 0.3f))
        
        // Configure the mock to return sample embeddings
        every { 
            embeddingModel.embed(any<String>()) 
        } returns
            mockk {
                every { content() } returns sampleEmbedding
            }
        
        // Configure the mock for embedAll
        every { 
            embeddingModel.embedAll(any<List<TextSegment>>()) 
        } returns
            mockk {
                every { content() } returns
                    listOf(
                        sampleEmbedding,
                        createSampleEmbedding(listOf(0.4f, 0.5f, 0.6f)),
                    )
            }
        
        // Create the service with the mock model
        embeddingService = OnnxEmbeddingService(embeddingModel)
    }

    @Test
    fun `embedText should return embedding vector for text`() {
        // Given
        val text = "This is a test"
        val expectedEmbedding = listOf(0.1f, 0.2f, 0.3f)
        
        // When
        val result = embeddingService.embedText(text)
        
        // Then
        assertEquals(expectedEmbedding, result)
    }

    @Test
    fun `embedTexts should return embeddings for multiple texts`() {
        // Given
        val texts = listOf("First text", "Second text")
        val expectedEmbeddings =
            listOf(
                listOf(0.1f, 0.2f, 0.3f),
                listOf(0.4f, 0.5f, 0.6f),
            )
        
        // When
        val result = embeddingService.embedTexts(texts)
        
        // Then
        assertEquals(expectedEmbeddings, result)
    }

    @Test
    fun `calculateSimilarity should return cosine similarity between embeddings`() {
        // Given
        val embedding1 = listOf(1.0f, 0.0f, 0.0f)
        val embedding2 = listOf(0.0f, 1.0f, 0.0f)
        val embedding3 = listOf(1.0f, 0.0f, 0.0f) // Same as embedding1
        
        // When
        val similarity1to2 = embeddingService.calculateSimilarity(embedding1, embedding2)
        val similarity1to3 = embeddingService.calculateSimilarity(embedding1, embedding3)
        
        // Then
        assertEquals(0.0f, similarity1to2, 0.001f) // Orthogonal vectors have 0 similarity
        assertEquals(1.0f, similarity1to3, 0.001f) // Identical vectors have similarity 1
    }

    // Helper method to create sample embeddings
    private fun createSampleEmbedding(values: List<Float>): Embedding = Embedding.from(values)
} 
