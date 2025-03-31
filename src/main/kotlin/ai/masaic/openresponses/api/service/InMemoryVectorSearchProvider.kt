package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of VectorSearchProvider.
 * 
 * This class provides a simple in-memory vector search implementation
 * that uses the EmbeddingService to generate and compare vector embeddings.
 * It's suitable for development and testing or small-scale applications.
 */
@Service
@ConditionalOnProperty(name = ["app.vector-store.provider"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryVectorSearchProvider(
    private val embeddingService: EmbeddingService,
    private val vectorSearchProperties: VectorSearchProperties,
) : VectorSearchProvider {
    // Map of fileId to file content chunks and their embeddings
    private val fileChunks = ConcurrentHashMap<String, List<ChunkWithEmbedding>>()
    
    // Map of fileId to metadata
    private val fileMetadata = ConcurrentHashMap<String, Map<String, Any>>()

    /**
     * Data class representing a chunk of text with its vector embedding.
     */
    private data class ChunkWithEmbedding(
        val fileId: String,
        val content: String,
        val embedding: List<Float>,
        val chunkMetadata: Map<String, Any> = emptyMap(),
    )

    /**
     * Indexes a file in the vector store by chunking its content and computing embeddings.
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
            
            // Split the content into chunks (simple implementation - could be improved)
            val chunks = chunkText(text, vectorSearchProperties.chunkSize, vectorSearchProperties.chunkOverlap)
            
            // Generate embeddings for each chunk
            val chunksWithEmbeddings =
                chunks.map { chunk ->
                    ChunkWithEmbedding(
                        fileId = fileId,
                        content = chunk,
                        embedding = embeddingService.embedText(chunk),
                        chunkMetadata = mapOf("filename" to filename),
                    )
                }
            
            // Store the chunks with embeddings
            fileChunks[fileId] = chunksWithEmbeddings
            
            // Store metadata
            fileMetadata[fileId] = mapOf("filename" to filename)
            
            return true
        } catch (e: Exception) {
            // Log the error
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
        val queryEmbedding = embeddingService.embedText(query)
        
        // Collect all chunks
        val allChunks = fileChunks.values.flatten()
        
        // Apply filters if provided
        val filteredChunks =
            if (filters != null) {
                allChunks.filter { chunk ->
                    filters.all { (key, value) ->
                        when {
                            key == "fileId" -> chunk.fileId == value
                            chunk.chunkMetadata.containsKey(key) -> chunk.chunkMetadata[key] == value
                            fileMetadata[chunk.fileId]?.containsKey(key) == true -> 
                                fileMetadata[chunk.fileId]?.get(key) == value
                            else -> false
                        }
                    }
                }
            } else {
                allChunks
            }
        
        // Calculate similarity scores
        val scoredChunks =
            filteredChunks.map { chunk ->
                val score = embeddingService.calculateSimilarity(queryEmbedding, chunk.embedding)
                chunk to score
            }
        
        // Sort by score (descending) and take top results
        return scoredChunks
            .sortedByDescending { (_, score) -> score }
            .take(maxResults)
            .map { (chunk, score) ->
                VectorSearchProvider.SearchResult(
                    fileId = chunk.fileId,
                    score = score.toDouble(),
                    content = chunk.content,
                    metadata = chunk.chunkMetadata + (fileMetadata[chunk.fileId] ?: emptyMap()),
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
        fileChunks.remove(fileId)
        fileMetadata.remove(fileId)
        return true
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
