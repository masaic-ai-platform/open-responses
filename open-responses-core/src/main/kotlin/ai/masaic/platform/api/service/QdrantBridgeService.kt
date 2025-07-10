package ai.masaic.platform.api.service

import ai.masaic.openresponses.api.config.QdrantVectorProperties
import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.util.context.Context
import java.time.Duration

@Service
class QdrantBridgeService(
    private val qdrantProperties: QdrantVectorProperties,
    private val vectorSearchProperties: VectorSearchConfigProperties,
    private val client: QdrantClient,
) {
    private val logger = KotlinLogging.logger {}
    private val defaultEmbeddingStore: QdrantEmbeddingStore

    init {
        createOpenResponsesCollection(vectorSearchProperties.collectionName)
        defaultEmbeddingStore = buildQdrantEmbeddingStore(client, vectorSearchProperties.collectionName)
        logger.info("Initialized Qdrant vector search provider with collection: {}", vectorSearchProperties.collectionName)
    }

    fun getQdrantEmbeddingStore(): QdrantEmbeddingStore {
        // Attempt to read user ID from reactive context
        try {
            // This will work when called from within a reactive context
            var u = "not_found"
            Mono.deferContextual<String> { ctx ->
                val user = ctx.getOrDefault("USER_ID", "unknown")
                logger.info("QdrantBridgeService.getQdrantEmbeddingStore: User ID from context: {}", user)
                Mono.just(user ?: "=======")
            }.subscribe { userId ->
                // User ID is logged above
                u = userId
                logger.info { "Found userId $userId" }
            }
            logger.info { "Found userId $u" }
        } catch (e: Exception) {
            logger.debug("Context not available: {}", e.message)
        }
        
        return defaultEmbeddingStore
    }

    private final fun createOpenResponsesCollection(collectionName: String) {
        try {
            val collections = client.listCollectionsAsync().get()
            if (collections.none { it == collectionName }) {
                client
                    .createCollectionAsync(
                        collectionName,
                        Collections.VectorParams
                            .newBuilder()
                            .setDistance(Collections.Distance.Cosine)
                            .setSize(vectorSearchProperties.vectorDimension.toLong())
                            .build(),
                    ).get()
                logger.info("Created Qdrant collection: {}", collectionName)

                listOf(
                    "file_id" to Collections.PayloadSchemaType.Keyword,
                    "text_segment" to Collections.PayloadSchemaType.Keyword,
                    "vector_store_id" to Collections.PayloadSchemaType.Keyword,
                    "chunk_index" to Collections.PayloadSchemaType.Integer,
                    "total_chunks" to Collections.PayloadSchemaType.Integer,
                    "chunk_id" to Collections.PayloadSchemaType.Keyword,
                    "category" to Collections.PayloadSchemaType.Keyword,
                    "filename" to Collections.PayloadSchemaType.Keyword,
                    "language" to Collections.PayloadSchemaType.Keyword,
                ).forEach { (field, type) ->
                    client
                        .createPayloadIndexAsync(
                            collectionName,
                            field,
                            type,
                            // indexParams=
                            null,
                            // waitForSync=
                            true,
                            // ordering=
                            null,
                            // timeout=
                            Duration.ofSeconds(10),
                        ).get() // wait for the async call to complete
                    logger.info("Created index: {}", field)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Qdrant collection: {}", e.message, e)
            throw RuntimeException("Failed to initialize Qdrant collection: ${e.message}", e)
        }
    }

    private fun buildQdrantEmbeddingStore(
        client: QdrantClient,
        collectionName: String,
    ): QdrantEmbeddingStore =
        QdrantEmbeddingStore
            .builder()
            .client(client)
            .host(qdrantProperties.host)
            .port(qdrantProperties.port)
            .useTls(qdrantProperties.useTls)
            .collectionName(collectionName)
            .apply { qdrantProperties.apiKey?.let { apiKey(it) } }
            .build()
}
