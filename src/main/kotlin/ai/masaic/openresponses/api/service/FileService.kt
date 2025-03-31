package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.model.FileDeleteResponse
import ai.masaic.openresponses.api.model.FileListResponse
import ai.masaic.openresponses.api.model.FilePurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Path
import java.time.Instant

/**
 * Service for handling file operations.
 *
 * This service provides methods for uploading, retrieving, listing, and deleting files.
 * It uses the configured FileStorageService for physical file storage.
 */
@Service
class FileService(
    @Autowired private val fileStorageService: FileStorageService,
    @Autowired(required = false) private val vectorSearchProvider: VectorSearchProvider?,
) {
    private val log = LoggerFactory.getLogger(FileService::class.java)

    init {
        registerVectorIndexingHook()
    }

    /**
     * Uploads a file.
     *
     * @param file The file to upload
     * @param purpose The purpose of the file
     * @return The uploaded file object
     */
    suspend fun uploadFile(
        file: MultipartFile,
        purpose: String,
    ): File =
        withContext(Dispatchers.IO) {
            // Validate purpose
            if (!FilePurpose.isValid(purpose)) {
                throw IllegalArgumentException("Invalid purpose: $purpose. Valid purposes are: ${FilePurpose.entries.joinToString()}")
            }
        
            // Store the file
            val fileId = fileStorageService.store(file, purpose)
        
            // Create file object
            File(
                id = fileId,
                bytes = file.size,
                filename = file.originalFilename ?: "unknown",
                purpose = purpose,
                createdAt = Instant.now().epochSecond,
            )
        }

    /**
     * Lists all files, with optional filtering.
     *
     * @param purpose Optional purpose to filter by
     * @param limit Maximum number of files to return
     * @param order Sort order (asc or desc)
     * @param after Return files after this ID (for pagination)
     * @return List of files
     */
    suspend fun listFiles(
        purpose: String? = null,
        limit: Int = 1000,
        order: String = "desc",
        after: String? = null,
    ): FileListResponse =
        withContext(Dispatchers.IO) {
            // Get files from storage as a flow
            val fileFlow =
                if (purpose != null) {
                    fileStorageService.loadByPurpose(purpose)
                } else {
                    fileStorageService.loadAll()
                }
        
            // Convert paths to file objects and collect as list
            val fileObjects =
                fileFlow
                    .map { path -> pathToFile(path) }
                    .toList()
                    .sortedBy { it.createdAt }
                    .let { if (order.lowercase() == "desc") it.reversed() else it }
        
            // Handle pagination
            val startIndex =
                if (after != null) {
                    val afterIndex = fileObjects.indexOfFirst { it.id == after }
                    if (afterIndex >= 0) afterIndex + 1 else 0
                } else {
                    0
                }
        
            // Apply limit
            val paginatedFiles =
                fileObjects
                    .drop(startIndex)
                    .take(limit.coerceIn(1, 10000))
            
            FileListResponse(data = paginatedFiles)
        }

    /**
     * Retrieves a file by ID.
     *
     * @param fileId The ID of the file to retrieve
     * @return The file object
     */
    suspend fun getFile(fileId: String): File =
        withContext(Dispatchers.IO) {
            if (!fileStorageService.exists(fileId)) {
                throw FileNotFoundException("File not found: $fileId")
            }
        
            // Get file metadata from storage
            val metadata = fileStorageService.getFileMetadata(fileId)
        
            File(
                id = fileId,
                bytes = metadata["bytes"] as Long,
                filename = metadata["filename"] as String,
                purpose = metadata["purpose"] as String,
                createdAt = metadata["created_at"] as Long,
            )
        }

    /**
     * Retrieves a file's content.
     *
     * @param fileId The ID of the file to retrieve
     * @return The file content as a Resource
     */
    suspend fun getFileContent(fileId: String): Resource =
        withContext(Dispatchers.IO) {
            fileStorageService.loadAsResource(fileId)
        }

    /**
     * Deletes a file.
     *
     * @param fileId The ID of the file to delete
     * @return Delete response
     */
    suspend fun deleteFile(fileId: String): FileDeleteResponse =
        withContext(Dispatchers.IO) {
            if (!fileStorageService.exists(fileId)) {
                throw FileNotFoundException("File not found: $fileId")
            }
        
            val deleted = fileStorageService.delete(fileId)

            vectorSearchProvider?.let { provider ->
                try {
                    log.info("Deleting file $fileId from vector store")
                    provider.deleteFile(fileId)
                } catch (e: Exception) {
                    log.error("Error deleting file $fileId from vector store", e)
                }
            }

            FileDeleteResponse(id = fileId, deleted = deleted)
        }

    /**
     * Registers a hook for indexing files into a vector store.
     * This is called during service initialization.
     */
    fun registerVectorIndexingHook() {
        // Register a hook to index files in the vector store if a provider is configured
        vectorSearchProvider?.let { provider ->
            fileStorageService.registerPostProcessHook { fileId, purpose ->
                try {
                    if (purpose in listOf("assistants", "user_data")) {
                        log.info("Indexing file $fileId for purpose $purpose in vector store")
                        val resource = fileStorageService.loadAsResource(fileId)
                        provider.indexFile(fileId, resource.inputStream, resource.filename ?: fileId)
                    }
                } catch (e: Exception) {
                    log.error("Error indexing file $fileId in vector store", e)
                }
            }
        }
    }

    /**
     * Converts a Path to a File object.
     */
    private suspend fun pathToFile(path: Path): File =
        withContext(Dispatchers.IO) {
            val fileId = path.fileName.toString()
            val metadata = fileStorageService.getFileMetadata(fileId)
        
            File(
                id = fileId,
                bytes = metadata["bytes"] as Long,
                filename = metadata["filename"] as String,
                purpose = metadata["purpose"] as String,
                createdAt = metadata["created_at"] as Long,
            )
        }
} 
