package ai.masaic.openresponses.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

/**
 * Main configuration class for storage-related settings.
 */
@Configuration
@EnableConfigurationProperties(
    FileStorageProperties::class,
    VectorRepositoryProperties::class,
    QdrantVectorProperties::class,
    VectorSearchConfigProperties::class,
)
class StorageConfiguration

/**
 * Properties for file storage configuration.
 */
@ConfigurationProperties(prefix = "open-responses.file-storage")
data class FileStorageProperties(
    /**
     * The type of file storage to use (local or s3).
     */
    val type: String = "local",
    /**
     * Local file storage configuration.
     */
    val local: LocalStorageConfig = LocalStorageConfig(),
    /**
     * Temporary directory for file uploads.
     */
    val tempDir: String = System.getProperty("java.io.tmpdir"),
) {
    /**
     * Gets the configured root directory with proper path handling.
     */
    fun getRootDirectory(): String =
        when (type) {
            "local" -> Paths.get(local.rootDir).toAbsolutePath().toString()
            else -> throw IllegalArgumentException("Unsupported storage type: $type")
        }

    /**
     * Configuration for local file storage.
     */
    data class LocalStorageConfig(
        /**
         * The root directory for local file storage.
         */
        val rootDir: String = "data/files",
        /**
         * Enable auto-creation of directories.
         */
        val createDirectories: Boolean = true,
    )
}

/**
 * Properties for vector store configuration.
 */
@ConfigurationProperties(prefix = "open-responses.store.vector")
data class VectorRepositoryProperties(
    /**
     * The type of repository to use for vector store metadata (file or mongodb).
     */
    val repository: RepositoryConfig = RepositoryConfig(),
) {
    /**
     * Configuration for vector store repository.
     */
    data class RepositoryConfig(
        /**
         * The type of repository to use (file or mongodb).
         */
        val type: String = "file",
    )
}

/**
 * Configuration properties for vector search.
 */
@ConfigurationProperties(prefix = "open-responses.store.vector.search")
data class VectorSearchConfigProperties(
    /**
     * The vector search provider to use (file or qdrant).
     */
    val provider: String = "file",
    /**
     * Default collection name for vector indices.
     */
    val collectionName: String = "open-responses",
    /**
     * Default chunk size for text chunking.
     */
    val chunkSize: Int = 1000,
    /**
     * Default chunk overlap for text chunking.
     */
    val chunkOverlap: Int = 200,
    /**
     * Default vector dimension for embeddings.
     */
    val vectorDimension: Int = 1536,
    /**
     * Path for file-based vector store.
     */
    val filePath: String = "data/vectors",
)

/**
 * Properties for Qdrant vector database configuration.
 */
@ConfigurationProperties(prefix = "open-responses.store.vector.search.qdrant")
data class QdrantVectorProperties(
    /**
     * The Qdrant host.
     */
    val host: String = "localhost",
    /**
     * The Qdrant port.
     */
    val port: Int = 6334,
    /**
     * Whether to use TLS for connecting to Qdrant.
     */
    val useTls: Boolean = false,
    /**
     * Minimum score for vector search results.
     */
    val minScore: Float? = 0.7f,
    /**
     * API key for Qdrant Cloud.
     */
    val apiKey: String? = null,
) 
