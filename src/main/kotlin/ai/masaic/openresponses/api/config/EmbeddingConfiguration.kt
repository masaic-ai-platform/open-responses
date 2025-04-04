package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.service.DefaultEmbeddingService
import ai.masaic.openresponses.api.service.EmbeddingService
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel
import dev.langchain4j.model.embedding.onnx.PoolingMode
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
     * Provides an EmbeddingService implementation.
     * 
     * @param embeddingModel The embedding model to use
     * @return An EmbeddingService instance
     */
    @Bean
    fun embeddingService(embeddingModel: EmbeddingModel): EmbeddingService = DefaultEmbeddingService(embeddingModel)
}
