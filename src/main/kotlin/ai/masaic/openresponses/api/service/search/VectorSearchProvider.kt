package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.model.ChunkingStrategy
import java.io.InputStream

/**
 * Interface for vector search providers.
 *
 * This interface defines methods for indexing and searching files in a vector database.
 * Implementations can use different vector stores such as Pinecone, Milvus, or others.
 */
interface VectorSearchProvider {
    /**
     * Indexes a file in the vector store.
     *
     * @param fileId The ID of the file
     * @param content The file content as an InputStream
     * @param filename The name of the file
     * @param chunkingStrategy Optional chunking strategy to use when splitting the text
     * @return True if indexing was successful, false otherwise
     */
    fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy? = null,
    ): Boolean

    /**
     * Searches for similar content in the vector store.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @param filters Optional filters to apply to the search
     * @return List of search results
     */
    fun searchSimilar(
        query: String,
        maxResults: Int = 10,
        filters: Map<String, Any>? = null,
    ): List<SearchResult>

    /**
     * Deletes a file from the vector store.
     *
     * @param fileId The ID of the file to delete
     * @return True if deletion was successful, false otherwise
     */
    fun deleteFile(fileId: String): Boolean

    /**
     * Gets metadata for a file from the vector store.
     *
     * @param fileId The ID of the file
     * @return Map of metadata, or null if the file doesn't exist
     */
    fun getFileMetadata(fileId: String): Map<String, Any>? = null

    /**
     * Represents a search result from the vector store.
     *
     * @property fileId The ID of the file
     * @property score The similarity score
     * @property content The matching content excerpt
     * @property metadata Additional metadata about the result
     */
    data class SearchResult(
        val fileId: String,
        val score: Double,
        val content: String,
        val metadata: Map<String, Any> = emptyMap(),
    )
}
