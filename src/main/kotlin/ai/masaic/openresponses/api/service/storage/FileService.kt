package ai.masaic.openresponses.api.service.storage

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.model.FileDeleteResponse
import ai.masaic.openresponses.api.model.FileListResponse
import ai.masaic.openresponses.api.model.FilePurpose
import ai.masaic.openresponses.api.service.search.VectorSearchProvider
import ai.masaic.openresponses.api.support.service.TelemetryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.Resource
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
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
    @Autowired private val telemetryService: TelemetryService,
) {
    private val log = LoggerFactory.getLogger(FileService::class.java)

    /**
     * Uploads a file using reactive FilePart.
     *
     * @param filePart The file part to upload
     * @param purpose The purpose of the file
     * @return The uploaded file object
     */
    suspend fun uploadFilePart(
        filePart: FilePart,
        purpose: String,
    ): File =
        withContext(Dispatchers.IO) {
            // Validate purpose
            if (!FilePurpose.Companion.isValid(purpose)) {
                throw IllegalArgumentException("Invalid purpose: $purpose. Valid purposes are: ${FilePurpose.entries.joinToString()}")
            }

            // Track the file upload operation with telemetry
            telemetryService.withFileOperation(
                operationName = "upload",
                fileId = "temp", // We don't have the ID yet, will be generated during storage
                fileName = filePart.filename(),
                purpose = purpose,
            ) {
                // Store the file
                val fileId = fileStorageService.store(filePart, purpose)

                // Create file object
                File(
                    id = fileId,
                    bytes = 0, // Size will be updated by storage service
                    filename = filePart.filename(),
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )
            }
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
            // Get all files
            val allFiles =
                if (purpose != null) {
                    fileStorageService.loadByPurpose(purpose).map { pathToFile(it) }.toList()
                } else {
                    fileStorageService.loadAll().map { pathToFile(it) }.toList()
                }

            // Sort by creation time
            val sortedFiles =
                if (order.equals("asc", ignoreCase = true)) {
                    allFiles.sortedBy { it.createdAt }
                } else {
                    allFiles.sortedByDescending { it.createdAt }
                }

            // Apply pagination
            val filteredFiles =
                if (after != null) {
                    val afterIndex = sortedFiles.indexOfFirst { it.id == after }
                    if (afterIndex >= 0 && afterIndex < sortedFiles.size - 1) {
                        sortedFiles.subList(afterIndex + 1, sortedFiles.size)
                    } else {
                        emptyList()
                    }
                } else {
                    sortedFiles
                }

            // Apply limit
            val limitedFiles = filteredFiles.take(limit)

            FileListResponse(data = limitedFiles)
        }

    /**
     * Retrieves a file.
     *
     * @param fileId The ID of the file to retrieve
     * @return The file
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
     * Converts a Path to a File object.
     */
    private suspend fun pathToFile(path: Path): File =
        withContext(Dispatchers.IO) {
            val fileId = path.fileName.toString()
            val metadata = fileStorageService.getFileMetadata(fileId)

            if (metadata.isEmpty()) {
                return@withContext File(
                    id = fileId,
                    bytes = 0,
                    filename = "",
                    purpose = "",
                    createdAt = Instant.now().epochSecond,
                )
            }
            File(
                id = fileId,
                bytes = metadata["bytes"] as Long,
                filename = metadata["filename"] as String,
                purpose = metadata["purpose"] as String,
                createdAt = metadata["created_at"] as Long,
            )
        }
}
