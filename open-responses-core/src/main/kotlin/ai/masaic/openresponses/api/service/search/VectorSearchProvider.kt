package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.RankingOptions
import java.io.InputStream

/**
 * Interface for vector search providers.
 *
 * This interface defines methods for indexing and searching files in a vector database.
 * Implementations can use different vector stores such as Pinecone, Milvus, or others.
 */
interface VectorSearchProvider {
    /**
     * Indexes a file for vector search.
     *
     * @param fileId The ID of the file
     * @param content The file content as an InputStream
     * @param filename The name of the file
     * @param chunkingStrategy Optional chunking strategy to use when splitting the text
     * @param preDeleteIfExists Whether to check and delete existing vectors for this file ID before indexing
     * @param attributes Additional metadata attributes to include with each vector
     * @param vectorStoreId The ID of the vector store this file belongs to
     * @return True if indexing was successful, false otherwise
     */
    suspend fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy? = null,
        preDeleteIfExists: Boolean = true,
        attributes: Map<String, Any>? = null,
        vectorStoreId: String,
    ): Boolean

    /**
     * Indexes a file for vector search with attributes.
     * Default implementation to maintain backward compatibility.
     *
     * @param fileId The ID of the file
     * @param content The file content as an InputStream
     * @param filename The name of the file
     * @param chunkingStrategy Optional chunking strategy to use when splitting the text
     * @param attributes Additional metadata attributes to include with each vector
     * @param vectorStoreId The ID of the vector store this file belongs to
     * @return True if indexing was successful, false otherwise
     */
    suspend fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy? = null,
        attributes: Map<String, Any>? = null,
        vectorStoreId: String,
    ): Boolean = indexFile(fileId, content, filename, chunkingStrategy, true, attributes, vectorStoreId)

    /**
     * Indexes a file for vector search.
     * Default implementation to maintain backward compatibility.
     *
     * @param fileId The ID of the file
     * @param content The file content as an InputStream
     * @param filename The name of the file
     * @param chunkingStrategy Optional chunking strategy to use when splitting the text
     * @param vectorStoreId The ID of the vector store this file belongs to
     * @return True if indexing was successful, false otherwise
     */
    suspend fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy? = null,
        vectorStoreId: String,
    ): Boolean = indexFile(fileId, content, filename, chunkingStrategy, true, null, vectorStoreId)

    /**
     * Searches for similar content in the vector store.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @param rankingOptions Optional ranking options for search
     * @return List of search results
     */
    fun searchSimilar(
        query: String,
        maxResults: Int = 10,
        rankingOptions: RankingOptions?,
    ): List<SearchResult>

    /**
     * Searches for similar content in the vector store with structured filter.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @param rankingOptions Optional ranking options for search
     * @param filter Optional structured filter (new format)
     * @return List of search results
     */
    fun searchSimilar(
        query: String,
        maxResults: Int = 10,
        rankingOptions: RankingOptions?,
        filter: Filter?,
    ): List<SearchResult> {
        // Default implementation delegates to the legacy method
        return searchSimilar(query, maxResults, rankingOptions)
    }

    /**
     * Deletes a file from the vector store.
     *
     * @param fileId The ID of the file to delete
     * @return True if deletion was successful, false otherwise
     */
    suspend fun deleteFile(fileId: String): Boolean

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
