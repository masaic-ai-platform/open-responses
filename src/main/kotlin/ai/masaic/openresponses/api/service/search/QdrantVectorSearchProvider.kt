package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.config.QdrantVectorProperties
import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.RankingOptions
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.utils.DocumentTextExtractor
import ai.masaic.openresponses.api.utils.FilterUtils
import ai.masaic.openresponses.api.utils.IdGenerator
import ai.masaic.openresponses.api.utils.TextChunkingUtil
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.InputStream

/**
 * Qdrant implementation of VectorSearchProvider.
 *
 * This class provides a VectorSearchProvider implementation that uses Qdrant
 * vector database for storing and searching vector embeddings.
 */
@Service
@ConditionalOnProperty(name = ["open-responses.store.vector.search.provider"], havingValue = "qdrant")
class QdrantVectorSearchProvider(
    private val embeddingService: EmbeddingService,
    private val qdrantProperties: QdrantVectorProperties,
    private val vectorSearchProperties: VectorSearchConfigProperties,
    client: QdrantClient,
) : VectorSearchProvider {
    private val log = LoggerFactory.getLogger(QdrantVectorSearchProvider::class.java)
    private val collectionName = vectorSearchProperties.collectionName
    private val vectorDimension: Long = vectorSearchProperties.vectorDimension.toLong()
    private val embeddingStore: QdrantEmbeddingStore

    init {
        // Create collection if it doesn't exist
        try {
            val collections = client.listCollectionsAsync().get()
            if (collections.none { it == collectionName }) {
                client
                    .createCollectionAsync(
                        collectionName,
                        Collections.VectorParams
                            .newBuilder()
                            .setDistance(Collections.Distance.Cosine)
                            .setSize(vectorDimension)
                            .build(),
                    ).get()
                log.info("Created Qdrant collection: {}", collectionName)
            }
        } catch (e: Exception) {
            log.error("Failed to initialize Qdrant collection: {}", e.message, e)
            throw RuntimeException("Failed to initialize Qdrant collection: ${e.message}", e)
        }

        // Initialize the embedding store
        embeddingStore =
            QdrantEmbeddingStore
                .builder()
                .host(qdrantProperties.host)
                .port(qdrantProperties.port)
                .useTls(qdrantProperties.useTls)
                .collectionName(collectionName)
                .build()

        log.info("Initialized Qdrant vector search provider with collection: {}", collectionName)
    }

    /**
     * Indexes a file in the vector store.
     *
     * @param fileId The ID of the file
     * @param inputStream The file content as an InputStream
     * @param filename The name of the file
     * @param chunkingStrategy Optional chunking strategy for the file
     * @param preDeleteIfExists Whether to check and delete existing vectors for this file before indexing (default: true)
     * @param attributes Additional metadata attributes to include with each vector (default: null)
     * @return True if indexing was successful, false otherwise
     */
    override fun indexFile(
        fileId: String,
        inputStream: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
        preDeleteIfExists: Boolean,
        attributes: Map<String, Any>?,
    ): Boolean {
        try {
            // Check if we need to delete existing file vectors
            if (preDeleteIfExists) {
                // Let's see if this file exists by trying to find metadata
                val existingMetadata = getFileMetadata(fileId)
                if (existingMetadata != null) {
                    log.info("File $fileId already exists in the vector store, deleting it for re-indexing")
                    deleteFile(fileId)
                }
            }
            
            // Extract text content from the document
            val text =
                try {
                    DocumentTextExtractor.Companion.extractAndCleanText(inputStream, filename)
                } catch (e: Exception) {
                    log.warn("Extracted text is empty for file: {}", filename)
                    return false
                }

            if (text.isBlank()) {
                log.warn("Extracted text is empty for file: {}", filename)
                return false
            }

            // Use the TextChunkingUtil to chunk the text
            log.debug("Chunking text for file: {}", filename)
            val textChunks = TextChunkingUtil.chunkText(text, chunkingStrategy)
            
            if (textChunks.isEmpty()) {
                log.warn("No chunks created for file: {}", filename)
                return false
            }
            
            log.info("Created {} chunks for file: {}", textChunks.size, filename)

            // Generate embeddings and store them
            var indexingSuccessful = true
            for (chunk in textChunks) {
                try {
                    // Generate embedding for the chunk
                    val embedding = embeddingService.embedText(chunk.text)

                    // Create metadata for this chunk
                    val metadata =
                        mutableMapOf<String, Any>(
                            "file_id" to fileId,
                            "filename" to filename,
                            "chunk_index" to chunk.index,
                            "chunk_id" to IdGenerator.generateChunkId(),
                            "total_chunks" to textChunks.size,
                        )
                    
                    // Add any additional user-provided attributes to the metadata
                    if (attributes != null) {
                        metadata.putAll(attributes)
                    }

                    // Store the embedding with metadata
                    embeddingStore.add(
                        Embedding.from(embedding),
                        TextSegment.from(chunk.text, Metadata.from(metadata)),
                    )
                } catch (e: Exception) {
                    log.error("Error indexing chunk {} for file {}: {}", chunk.index, filename, e.message, e)
                    indexingSuccessful = false
                }
            }

            log.info("Successfully indexed file: {} with {} chunks", filename, textChunks.size)
            return indexingSuccessful
        } catch (e: Exception) {
            log.error("Error indexing file {}: {}", filename, e.message, e)
            return false
        }
    }

    /**
     * Indexes a file with attributes.
     */
    override fun indexFile(
        fileId: String,
        inputStream: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
        attributes: Map<String, Any>?,
    ): Boolean = indexFile(fileId, inputStream, filename, chunkingStrategy, true, attributes)

    /**
     * Indexes a file using the base interface method.
     */
    override fun indexFile(
        fileId: String,
        inputStream: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
    ): Boolean = indexFile(fileId, inputStream, filename, chunkingStrategy, true, null)

    /**
     * Searches for similar content in the vector store.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @param rankingOptions Optional ranking options for search
     * @param filter Optional structured filter object (new format)
     * @return List of search results
     */
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        rankingOptions: RankingOptions?,
        filter: Filter?,
    ): List<VectorSearchProvider.SearchResult> {
        try {
            // Return empty results for empty query
            if (query.isBlank()) {
                log.debug("Empty query provided, returning empty results")
                return emptyList()
            }

            // Generate embedding for the query
            val queryEmbedding = Embedding.from(embeddingService.embedText(query))

            // Find relevant documents
            val minScore = rankingOptions?.scoreThreshold ?: qdrantProperties.minScore ?: 0.07

            // Convert filter to Qdrant filter
            val qdrantFilter = filter?.let { FilterUtils.convertToQdrantFilter(it) }
            
            // Create search request
            val searchBuilder =
                EmbeddingSearchRequest
                    .builder()
                    .minScore(minScore.toDouble())
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)

            // Add filter if available
            if (qdrantFilter != null) {
                searchBuilder.filter(qdrantFilter)
            }

            // Execute search
            val matches = embeddingStore.search(searchBuilder.build()).matches()

            // Convert to search results
            val results =
                matches.map { match ->
                    val segment = match.embedded()
                    val metadata = segment.metadata().toMap()

                    VectorSearchProvider.SearchResult(
                        fileId = (metadata["file_id"] ?: "") as String,
                        score = match.score(),
                        content = segment.text(),
                        metadata = metadata,
                    )
                }

            log.debug("Found {} results for query", results.size)
            return results
        } catch (e: Exception) {
            log.error("Error searching similar content: {}", e.message, e)
            return emptyList()
        }
    }

    /**
     * Searches for similar content in the vector store using the base interface.
     */
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        rankingOptions: RankingOptions?,
    ): List<VectorSearchProvider.SearchResult> = searchSimilar(query, maxResults, rankingOptions, null)

    /**
     * Deletes a file from the vector store.
     *
     * @param fileId The ID of the file to delete
     * @return True if deletion was successful, false otherwise
     */
    override fun deleteFile(fileId: String): Boolean {
        try {
            // Delete points with matching fileId
            embeddingStore.removeAll(IsEqualTo("file_id", fileId))
            log.info("Deleted embeddings for file: {}", fileId)
            return true
        } catch (e: Exception) {
            log.error("Error deleting file {}: {}", fileId, e.message, e)
            return false
        }
    }

    /**
     * Gets metadata for a file from the vector store.
     *
     * @param fileId The ID of the file to get metadata for
     * @return Map of metadata, or null if the file doesn't exist
     */
    override fun getFileMetadata(fileId: String): Map<String, Any>? {
        try {
            // Create a dummy embedding for search - we're only using filter but API requires non-null embedding
            val dummyEmbedding = Embedding.from(FloatArray(vectorDimension.toInt()) { 0f })

            // Create a search request with fileId filter
            val searchRequest =
                EmbeddingSearchRequest
                    .builder()
                    .filter(IsEqualTo("file_id", fileId))
                    .maxResults(1)
                    .queryEmbedding(dummyEmbedding)
                    .build()

            // Search for any segment with this fileId
            val matches = embeddingStore.search(searchRequest).matches()
            if (matches.isEmpty()) return null

            // Return metadata from the first segment
            return matches
                .first()
                .embedded()
                .metadata()
                .toMap()
        } catch (e: Exception) {
            log.error("Error getting metadata for file {}: {}", fileId, e.message, e)
            return null
        }
    }
}
