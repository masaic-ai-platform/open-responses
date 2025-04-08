package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.service.embedding.DefaultEmbeddingService
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.embedding.OpenAIEmbeddingService
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel
import dev.langchain4j.model.embedding.onnx.PoolingMode
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
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
    val httpEnabled: Boolean = false,
    // The OpenAI model name (if using OpenAI embeddings)
    val model: String = "",
    // The OpenAI API base URL (if using OpenAI embeddings)
    val url: String = "",
)

/**
 * Configuration for embedding models.
 */
@Configuration
class EmbeddingConfiguration {
    /**
     * Provides an EmbeddingModel based on configuration properties.
     * 
     * @param properties The embedding configuration properties
     * @return An EmbeddingModel instance
     */
    @Bean
    @ConditionalOnProperty(name = ["open-responses.embeddings.http-enabled"], havingValue = "false", matchIfMissing = true)
    fun embeddingModel(properties: EmbeddingProperties): EmbeddingModel =
        when (properties.modelType) {
            "all-minilm-l6-v2" -> {
                try {
                    AllMiniLmL6V2EmbeddingModel()
                } catch (e: Exception) {
                    throw RuntimeException("Failed to load AllMiniLmL6V2EmbeddingModel. Make sure the dependency is properly included.", e)
                }
            }
            "onnx" -> {
                val pathToModel = properties.onnxModelPath ?: throw IllegalArgumentException("Missing 'onnxModelPath' property")
                val pathToTokenizer = properties.tokenizerPath ?: throw IllegalArgumentException("Missing 'tokenizerPath' property")
                val poolingMode = PoolingMode.valueOf(properties.poolingMode.uppercase())
                OnnxEmbeddingModel(pathToModel, pathToTokenizer, poolingMode)
            }
            // More model types can be added here
            else -> throw IllegalArgumentException("Unsupported model type: ${properties.modelType}")
        }

    /**
     * Creates a default embedding service using the provided embedding model.
     * This bean is used when openai.embeddings.enabled is set to false or not specified.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = ["open-responses.embeddings.http-enabled"], havingValue = "false", matchIfMissing = true)
    fun defaultEmbeddingService(embeddingModel: EmbeddingModel): EmbeddingService = DefaultEmbeddingService(embeddingModel)

    /**
     * Creates an OpenAI embedding service when openai.embeddings.enabled is true.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = ["open-responses.embeddings.http-enabled"], havingValue = "true")
    fun openAIEmbeddingService(properties: EmbeddingProperties): EmbeddingService =
        OpenAIEmbeddingService(
            apiKey = properties.apiKey,
            modelName = properties.model,
            baseUrl = properties.url,
        )
}
