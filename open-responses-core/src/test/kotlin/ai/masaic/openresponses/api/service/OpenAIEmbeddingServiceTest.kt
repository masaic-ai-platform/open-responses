package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.service.embedding.OpenAIEmbeddingService
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.model.openaiofficial.OpenAiOfficialEmbeddingModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpenAIEmbeddingServiceTest {
    private lateinit var openAiEmbeddingModel: OpenAiOfficialEmbeddingModel
    private lateinit var embeddingService: OpenAIEmbeddingService

    @BeforeEach
    fun setup() {
        openAiEmbeddingModel =
            mockk {
                every { embedAll(any()) } returns
                    mockk {
                        every { content() } returns
                            listOf(
                                mockk {
                                    every { vectorAsList() } returns listOf(0.1f, 0.2f, 0.3f)
                                },
                                mockk {
                                    every { vectorAsList() } returns listOf(0.4f, 0.5f, 0.6f)
                                },
                            )
                    }
            }
        
        // Create the service with test values
        embeddingService =
            OpenAIEmbeddingService(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-api-key",
                modelName = "text-embedding-3-small",
            )
        
        // Use reflection to set the mocked model
        val field = OpenAIEmbeddingService::class.java.getDeclaredField("embeddingModel")
        field.isAccessible = true
        val lazyField = field.get(embeddingService) as Lazy<*>
        val lazyFieldImpl = Class.forName("kotlin.InitializedLazyImpl").getConstructor(Any::class.java)
        field.set(embeddingService, lazyFieldImpl.newInstance(openAiEmbeddingModel))
    }

    @Test
    fun `embedText should return embedding vector for single text`() {
        // Given
        val text = "Test text"
        val expectedEmbedding = listOf(0.1f, 0.2f, 0.3f)
        val embeddingResult =
            mockk<Embedding> {
                every { vectorAsList() } returns expectedEmbedding
            }
        
        every { 
            openAiEmbeddingModel.embed(text)
        } returns
            mockk {
                every { content() } returns embeddingResult
            }

        // When
        val result = embeddingService.embedText(text)

        // Then
        assertEquals(expectedEmbedding, result)
        verify(exactly = 1) { openAiEmbeddingModel.embed(text) }
    }

    @Test
    fun `embedTexts should return embeddings for multiple texts`() {
        // Given
        val texts = listOf("First text", "Second text")
        val embedding1 = listOf(0.1f, 0.2f, 0.3f)
        val embedding2 = listOf(0.4f, 0.5f, 0.6f)
        
        val embeddingResult1 =
            mockk<Embedding> {
                every { vectorAsList() } returns embedding1
            }
        val embeddingResult2 =
            mockk<Embedding> {
                every { vectorAsList() } returns embedding2
            }

        every { 
            openAiEmbeddingModel.embed("First text")
        } returns
            mockk {
                every { content() } returns embeddingResult1
            }
        
        every { 
            openAiEmbeddingModel.embed("Second text")
        } returns
            mockk {
                every { content() } returns embeddingResult2
            }

        // When
        val result = embeddingService.embedTexts(texts)

        // Then
        assertEquals(listOf(embedding1, embedding2), result)
        verify(exactly = 1) { openAiEmbeddingModel.embedAll(any()) }
    }

    @Test
    fun `calculateSimilarity should return correct cosine similarity`() {
        // Given
        val embedding1 = listOf(1.0f, 0.0f, 0.0f)
        val embedding2 = listOf(0.0f, 1.0f, 0.0f)
        val embedding3 = listOf(1.0f, 0.0f, 0.0f)

        // When
        val similarity1to2 = embeddingService.calculateSimilarity(embedding1, embedding2)
        val similarity1to3 = embeddingService.calculateSimilarity(embedding1, embedding3)

        // Then
        assertEquals(0.0f, similarity1to2, 0.001f)
        assertEquals(1.0f, similarity1to3, 0.001f)
    }

    @Test
    fun `embedText should handle empty text`() {
        // Given
        val text = ""
        val expectedEmbedding = listOf(0.0f, 0.0f, 0.0f)
        val embeddingResult =
            mockk<Embedding> {
                every { vectorAsList() } returns expectedEmbedding
            }
        
        every { 
            openAiEmbeddingModel.embed(text)
        } returns
            mockk {
                every { content() } returns embeddingResult
            }

        // When
        val result = embeddingService.embedText(text)

        // Then
        assertEquals(expectedEmbedding, result)
    }
} 
