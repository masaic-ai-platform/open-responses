package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.model.StaticChunkingConfig
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for vector search providers.
 */
@Configuration
class VectorSearchConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["open-responses.store.vector.search.provider"], havingValue = "qdrant")
    fun qdrantClient(qdrantProperties: QdrantVectorProperties): QdrantClient =
        QdrantClient(
            QdrantGrpcClient
                .newBuilder(
                    qdrantProperties.host,
                    qdrantProperties.port,
                    qdrantProperties.useTls,
                ).build(),
        )

    @Bean
    @ConditionalOnMissingBean(StaticChunkingConfig::class)
    fun staticChunkingConfig() = StaticChunkingConfig(1000, 200)
} 
