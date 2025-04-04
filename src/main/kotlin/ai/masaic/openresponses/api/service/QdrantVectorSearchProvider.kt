package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.QdrantProperties
import ai.masaic.openresponses.api.config.VectorSearchProperties
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.filter.Filter
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

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
            }
        } catch (e: Exception) {
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
            // Read the file content
            val text = BufferedReader(InputStreamReader(content)).use { it.readText() }
            
            // Split the content into chunks
            val chunks = chunkText(text, vectorSearchProperties.chunkSize, vectorSearchProperties.chunkOverlap)
            
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
            
            return true
        } catch (e: Exception) {
            println("Error indexing file: ${e.message}")
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
        // Generate embedding for the query
        val queryEmbedding = Embedding.from(embeddingService.embedText(query))
        
        // Find relevant documents
        val minScore = qdrantProperties.minScore ?: 0.0f
        
        // Search the store with filter
        val matches =
            if (filters != null && filters.isNotEmpty()) {
                // Convert filter to Qdrant filter format
                val qdrantFilter = createQdrantFilter(filters)
                embeddingStore
                    .search(
                        EmbeddingSearchRequest
                            .builder()
                            .minScore(minScore.toDouble())
                            .queryEmbedding(queryEmbedding)
                            .filter(qdrantFilter)
                            .build(),
                    ).matches()
            } else {
                embeddingStore.findRelevant(queryEmbedding, maxResults, minScore.toDouble())
            }
        
        // Convert to search results
        return matches.map { match ->
            val segment = match.embedded()
            val metadata = segment.metadata().toMap()
            
            VectorSearchProvider.SearchResult(
                fileId = (metadata["fileId"] ?: "") as String,
                score = match.score(),
                content = segment.text(),
                metadata = metadata,
            )
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
            return true
        } catch (e: Exception) {
            println("Error deleting file: ${e.message}")
            return false
        }
    }

    /**
     * Helper function to create a Qdrant filter from a map of filters.
     */
    private fun createQdrantFilter(filters: Map<String, Any>): Filter {
        filters.run {
            val key = keys.first()
            val value = values.first()
            val first: Filter = IsEqualTo(key, value)

            return filters.keys.drop(1).fold(first) { acc, key ->
                val value = filters[key]
                acc.and(IsEqualTo(key, value))
            }
        }
    }

    /**
     * Helper function to split text into overlapping chunks.
     *
     * @param text The text to split
     * @param chunkSize The size of each chunk
     * @param overlap The overlap between chunks
     * @return List of text chunks
     */
    private fun chunkText(
        text: String,
        chunkSize: Int,
        overlap: Int,
    ): List<String> {
        if (text.length <= chunkSize) {
            return listOf(text)
        }
        
        val chunks = mutableListOf<String>()
        var start = 0
        
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start += (chunkSize - overlap)
            
            // Prevent infinite loop if overlap >= chunkSize
            if (start <= 0) {
                start = end
            }
        }
        
        return chunks
    }
} 
