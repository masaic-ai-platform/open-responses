package ai.masaic.openresponses.api.service.storage

import kotlinx.coroutines.flow.Flow
import org.springframework.core.io.Resource
import org.springframework.http.codec.multipart.FilePart
import java.nio.file.Path

/**
 * Service interface for handling file storage operations.
 * This interface supports both local filesystem and S3 storage.
 */
interface FileStorageService {
    /**
     * Stores a file with the given purpose.
     *
     * @param file The file to store
     * @param purpose The file's purpose
     * @return The stored file's ID
     */
    suspend fun store(
        file: FilePart,
        purpose: String,
    ): String

    /**
     * Loads all files from storage.
     *
     * @return A flow of all file paths
     */
    fun loadAll(): Flow<Path>

    /**
     * Loads files with the specified purpose.
     *
     * @param purpose The purpose to filter by
     * @return A flow of file paths matching the purpose
     */
    fun loadByPurpose(purpose: String): Flow<Path>

    /**
     * Loads a file by its ID.
     *
     * @param fileId The ID of the file to load
     * @return The file path
     */
    suspend fun load(fileId: String): Path

    /**
     * Loads a file as a resource for downloading.
     *
     * @param fileId The ID of the file to load
     * @return The file as a Resource
     */
    suspend fun loadAsResource(fileId: String): Resource

    /**
     * Deletes a file by its ID.
     *
     * @param fileId The ID of the file to delete
     * @return True if the file was deleted, false otherwise
     */
    suspend fun delete(fileId: String): Boolean

    /**
     * Gets metadata about a specific file.
     *
     * @param fileId The ID of the file
     * @return Map of metadata key-value pairs
     */
    suspend fun getFileMetadata(fileId: String): Map<String, Any>

    /**
     * Checks if a file exists.
     *
     * @param fileId The ID of the file to check
     * @return True if the file exists, false otherwise
     */
    suspend fun exists(fileId: String): Boolean

    /**
     * Registers a hook for post-processing the file.
     * This can be used for vector indexing or other processing.
     *
     * @param hook The hook function to register
     */
    fun registerPostProcessHook(hook: suspend (String, String) -> Unit)
}
