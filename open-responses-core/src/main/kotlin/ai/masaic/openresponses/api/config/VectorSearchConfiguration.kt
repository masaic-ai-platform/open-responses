package ai.masaic.openresponses.api.config

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
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
                ).apply { qdrantProperties.apiKey?.let { withApiKey(it) } }
                .build(),
        )
}
