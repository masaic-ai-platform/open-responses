package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.QdrantProperties
import ai.masaic.openresponses.api.config.VectorSearchProperties
import ai.masaic.openresponses.api.utils.DocumentTextExtractor
import ai.masaic.openresponses.api.utils.TextChunkingUtil
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.filter.Filter
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
@ConditionalOnProperty(name = ["open-responses.vector-store.provider"], havingValue = "qdrant")
class QdrantVectorSearchProvider(
    private val embeddingService: EmbeddingService,
    private val qdrantProperties: QdrantProperties,
    private val vectorSearchProperties: VectorSearchProperties,
    client: QdrantClient,
) : VectorSearchProvider {
    private val log = LoggerFactory.getLogger(QdrantVectorSearchProvider::class.java)
    private val collectionName = qdrantProperties.collectionName ?: "open-responses-documents"
    private val vectorDimension: Long = qdrantProperties.vectorDimension.toLong()
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
     * @param content The file content as an InputStream
     * @param filename The name of the file
     * @return True if indexing was successful, false otherwise
     */
    override fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
    ): Boolean {
        try {
            // Extract text from the document using Apache Tika
            val text = DocumentTextExtractor.extractAndCleanText(content, filename)
            if (text.isBlank()) {
                log.warn("Extracted text is empty for file: {}", filename)
                return false
            }
            
            // Split the content into chunks using the common utility
            val chunks = TextChunkingUtil.chunkText(text, vectorSearchProperties.chunkSize, vectorSearchProperties.chunkOverlap)
            log.debug("Split file {} into {} chunks", filename, chunks.size)
            
            // Process each chunk
            chunks.forEachIndexed { index, chunk ->
                // Create metadata
                val metadata =
                    mapOf(
                        "fileId" to fileId,
                        "filename" to filename,
                        "chunkIndex" to index,
                    )

                // Convert to TextSegment with metadata
                val segment = TextSegment.from(chunk, Metadata.from(metadata))
                
                // Generate embedding
                val embedding = Embedding.from(embeddingService.embedText(chunk))
                
                // Add to embedding store
                embeddingStore.add(embedding, segment)
            }
            
            log.info("Successfully indexed file: {}", filename)
            return true
        } catch (e: Exception) {
            log.error("Error indexing file {}: {}", filename, e.message, e)
            return false
        }
    }

    /**
     * Searches for similar content in the vector store.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @param filters Optional filters to apply to the search
     * @return List of search results
     */
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        filters: Map<String, Any>?,
    ): List<VectorSearchProvider.SearchResult> {
        try {
            // Generate embedding for the query
            val queryEmbedding = Embedding.from(embeddingService.embedText(query))
            
            // Find relevant documents
            val minScore = qdrantProperties.minScore ?: 0.0f
            
            // Search the store with filter
            val matches =
                if (filters != null && filters.isNotEmpty()) {
                    log.debug("Searching with filters: {}", filters)
                    // Convert filter to Qdrant filter format
                    val qdrantFilter = createQdrantFilter(filters)
                    val searchBuilder =
                        EmbeddingSearchRequest
                            .builder()
                            .minScore(minScore.toDouble())
                            .queryEmbedding(queryEmbedding)
                            .maxResults(maxResults)
    
                    if (qdrantFilter != null) {
                        searchBuilder.filter(qdrantFilter)
                    }
    
                    embeddingStore.search(searchBuilder.build()).matches()
                } else {
                    embeddingStore.findRelevant(queryEmbedding, maxResults, minScore.toDouble())
                }
            
            // Convert to search results
            val results =
                matches.map { match ->
                    val segment = match.embedded()
                    val metadata = segment.metadata().toMap()
                
                    VectorSearchProvider.SearchResult(
                        fileId = (metadata["fileId"] ?: "") as String,
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
     * Deletes a file from the vector store.
     *
     * @param fileId The ID of the file to delete
     * @return True if deletion was successful, false otherwise
     */
    override fun deleteFile(fileId: String): Boolean {
        try {
            // Delete points with matching fileId
            embeddingStore.removeAll(IsEqualTo("fileId", fileId))
            log.info("Deleted embeddings for file: {}", fileId)
            return true
        } catch (e: Exception) {
            log.error("Error deleting file {}: {}", fileId, e.message, e)
            return false
        }
    }

    /**
     * Helper function to create a Qdrant filter from a map of filters.
     */
    private fun createQdrantFilter(filters: Map<String, Any>): Filter? {
        // Use a mutable reference to avoid smart cast issues with captured variables
        val filterRef = arrayOfNulls<Filter>(1)
        
        filters.forEach { (key, value) ->
            when {
                key == "fileIds" && value is List<*> -> {
                    // Handle the fileIds list by creating an OR filter for each fileId
                    val fileIdsList = value.filterIsInstance<String>()
                    if (fileIdsList.isNotEmpty()) {
                        val firstFilter = IsEqualTo("fileId", fileIdsList.first())
                        val fileIdsFilter =
                            fileIdsList.drop(1).fold(firstFilter) { acc, fileId ->
                                acc.or(IsEqualTo("fileId", fileId)) as IsEqualTo
                            }
                        
                        filterRef[0] = if (filterRef[0] == null) fileIdsFilter else filterRef[0]!!.and(fileIdsFilter)
                    }
                }
                else -> {
                    // Handle regular key-value filters
                    val newFilter = IsEqualTo(key, value)
                    filterRef[0] = if (filterRef[0] == null) newFilter else filterRef[0]!!.and(newFilter)
                }
            }
        }
        
        // Return default filter if no valid filters (matches all records)
        return filterRef[0]
    }
} 
