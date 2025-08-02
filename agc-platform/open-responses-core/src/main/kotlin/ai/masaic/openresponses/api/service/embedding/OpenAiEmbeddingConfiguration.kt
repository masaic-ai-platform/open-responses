package ai.masaic.openresponses.api.service.embedding

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Configuration properties for embedding models.
 */
@ConfigurationProperties(prefix = "open-responses.embeddings")
data class EmbeddingProperties(
    // The type of embedding model to use (e.g., "all-minilm-l6-v2", "custom")
    val modelType: String = "all-minilm-l6-v2",
    // Path to custom ONNX model file (if using a custom model)
    val onnxModelPath: String? = null,
    // Path to custom tokenizer JSON file (if using a custom model)
    val tokenizerPath: String? = null,
    // Pooling mode for custom models (e.g., "mean", "cls", "max")
    val poolingMode: String = "mean",
    // The OpenAI API key (if using OpenAI embeddings)
    val apiKey: String = "",
    // The OpenAI model name (if using OpenAI embeddings)
    val httpEnabled: Boolean = true,
    // The OpenAI model name (if using OpenAI embeddings)
    val model: String = "text-embedding-3-small",
    // The OpenAI API base URL (if using OpenAI embeddings)
    val url: String = "https://api.openai.com/v1",
)

/**
 * Configuration for embedding models.
 */
@Configuration
class OpenAiEmbeddingConfiguration {
    /**
     * Creates an OpenAI embedding service when openai.embeddings.enabled is true.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = ["open-responses.embeddings.http-enabled"], havingValue = "true", matchIfMissing = true)
    fun openAIEmbeddingService(properties: EmbeddingProperties): EmbeddingService =
        OpenAIEmbeddingService(
            apiKey = properties.apiKey,
            modelName = properties.model,
            baseUrl = properties.url,
        )
}
