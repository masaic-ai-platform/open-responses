package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.service.embedding.EmbeddingProperties
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.embedding.OnnxEmbeddingService
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel
import dev.langchain4j.model.embedding.onnx.PoolingMode
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

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
    @ConditionalOnProperty(name = ["open-responses.embeddings.http-enabled"], havingValue = "false")
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
    @ConditionalOnProperty(name = ["open-responses.embeddings.http-enabled"], havingValue = "false")
    fun defaultEmbeddingService(embeddingModel: EmbeddingModel): EmbeddingService = OnnxEmbeddingService(embeddingModel)
}
