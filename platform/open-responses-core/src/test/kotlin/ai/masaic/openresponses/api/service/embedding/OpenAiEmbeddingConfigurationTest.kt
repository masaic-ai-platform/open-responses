package ai.masaic.openresponses.api.service.embedding

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class OpenAiEmbeddingConfigurationTest {
    private val configuration = OpenAiEmbeddingConfiguration()

    @Test
    fun `openAIEmbeddingService should create service with provided properties`() {
        // Given
        val properties =
            EmbeddingProperties(
                apiKey = "test-api-key",
                model = "text-embedding-3-small",
                url = "https://api.openai.com/v1",
            )

        // When
        val service = configuration.openAIEmbeddingService(properties)

        // Then
        assertTrue(service is OpenAIEmbeddingService)
    }
}
