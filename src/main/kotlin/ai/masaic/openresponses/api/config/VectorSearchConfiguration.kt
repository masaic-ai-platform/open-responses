package ai.masaic.openresponses.api.config

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration properties for vector search.
 */
@ConfigurationProperties(prefix = "open-responses.vector-store")
data class VectorSearchProperties(
    /**
     * The vector store provider to use.
     * Supported values: "file-based", "in-memory", "qdrant"
     */
    val provider: String = "file-based",
    /**
     * The chunk size for text splitting.
     */
    val chunkSize: Int = 1000,
    /**
     * The overlap between chunks.
     */
    val chunkOverlap: Int = 200,
    val qdrant: QdrantProperties = QdrantProperties(),
)

/**
 * Configuration properties for Qdrant vector database.
 */
@ConfigurationProperties(prefix = "open-responses.vector-store.qdrant")
data class QdrantProperties(
    /**
     * Qdrant server hostname.
     */
    val host: String = "localhost",
    /**
     * Qdrant server port for gRPC.
     */
    val port: Int = 6334,
    /**
     * Whether to use TLS for connecting to Qdrant.
     */
    val useTls: Boolean = false,
    /**
     * Name of the collection to use. If null, a random name will be generated.
     */
    val collectionName: String? = null,
    /**
     * Minimum similarity score threshold for search results.
     */
    val minScore: Float? = 0.0f,
    /**
     * Vector dimension - depends on the embedding model used.
     * For AllMiniLmL6V2, this is 384.
     */
    val vectorDimension: Int = 384,
)

/**
 * Configuration for vector search providers.
 */
@Configuration
class VectorSearchConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["open-responses.vector-store.provider"], havingValue = "qdrant")
    fun qdrantClient(qdrantProperties: QdrantProperties): QdrantClient =
        QdrantClient(
            QdrantGrpcClient
                .newBuilder(
                    qdrantProperties.host,
                    qdrantProperties.port,
                    qdrantProperties.useTls,
                ).build(),
        )
} 
