package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile

/**
 * Repository interface for vector stores.
 *
 * This interface defines methods for storing and retrieving vector stores and their files.
 */
interface VectorStoreRepository {
    /**
     * Saves a vector store.
     *
     * @param vectorStore The vector store to save
     * @return The saved vector store
     */
    suspend fun saveVectorStore(vectorStore: VectorStore): VectorStore

    /**
     * Finds a vector store by ID.
     *
     * @param vectorStoreId The ID of the vector store to find
     * @return The vector store, or null if not found
     */
    suspend fun findVectorStoreById(vectorStoreId: String): VectorStore?

    /**
     * Lists all vector stores.
     *
     * @param limit Maximum number of vector stores to return
     * @param order Sort order (asc or desc)
     * @param after Return vector stores after this ID (for pagination)
     * @param before Return vector stores before this ID (for pagination)
     * @return List of vector stores
     */
    suspend fun listVectorStores(
        limit: Int = 20,
        order: String = "desc",
        after: String? = null,
        before: String? = null,
    ): List<VectorStore>

    /**
     * Deletes a vector store.
     *
     * @param vectorStoreId The ID of the vector store to delete
     * @return True if the vector store was deleted, false otherwise
     */
    suspend fun deleteVectorStore(vectorStoreId: String): Boolean

    /**
     * Saves a vector store file.
     *
     * @param vectorStoreFile The vector store file to save
     * @return The saved vector store file
     */
    suspend fun saveVectorStoreFile(vectorStoreFile: VectorStoreFile): VectorStoreFile

    /**
     * Finds a vector store file by ID.
     *
     * @param vectorStoreId The ID of the vector store
     * @param fileId The ID of the file
     * @return The vector store file, or null if not found
     */
    suspend fun findVectorStoreFileById(
        vectorStoreId: String,
        fileId: String,
    ): VectorStoreFile?

    /**
     * Lists all files in a vector store.
     *
     * @param vectorStoreId The ID of the vector store
     * @param limit Maximum number of files to return
     * @param order Sort order (asc or desc)
     * @param after Return files after this ID (for pagination)
     * @param before Return files before this ID (for pagination)
     * @param filter Filter by file status
     * @return List of vector store files
     */
    suspend fun listVectorStoreFiles(
        vectorStoreId: String,
        limit: Int = 20,
        order: String = "desc",
        after: String? = null,
        before: String? = null,
        filter: String? = null,
    ): List<VectorStoreFile>

    /**
     * Deletes a vector store file.
     *
     * @param vectorStoreId The ID of the vector store
     * @param fileId The ID of the file
     * @return True if the file was deleted, false otherwise
     */
    suspend fun deleteVectorStoreFile(
        vectorStoreId: String,
        fileId: String,
    ): Boolean
} 
