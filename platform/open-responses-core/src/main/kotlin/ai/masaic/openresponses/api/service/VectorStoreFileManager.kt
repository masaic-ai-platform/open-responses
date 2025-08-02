package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.exception.FileNotFoundException
import ai.masaic.openresponses.api.exception.VectorIndexingException
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.VectorStoreFile
import ai.masaic.openresponses.api.model.VectorStoreSearchResultContent
import ai.masaic.openresponses.api.repository.VectorStoreRepository
import ai.masaic.openresponses.api.service.search.VectorSearchProvider
import ai.masaic.openresponses.api.service.storage.FileStorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

/**
 * Service for managing files in vector stores.
 *
 * This service handles operations related to files in vector stores, including:
 * - Indexing files in the vector search provider
 * - Retrieving file content
 * - Checking file existence and metadata
 *
 * It decouples the VectorStoreService from direct file management concerns.
 */
@Service
class VectorStoreFileManager(
    @Autowired private val fileStorageService: FileStorageService,
    @Autowired private val vectorStoreRepository: VectorStoreRepository,
    @Autowired(required = false) private val vectorSearchProvider: VectorSearchProvider,
) {
    private val log = LoggerFactory.getLogger(VectorStoreFileManager::class.java)

    // Scope for processing files asynchronously
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Checks if a file exists in the storage.
     *
     * @param fileId The ID of the file to check
     * @return True if the file exists, false otherwise
     */
    suspend fun fileExists(fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            fileStorageService.exists(fileId)
        }

    /**
     * Gets file metadata from storage.
     *
     * @param fileId The ID of the file
     * @return Map of file metadata
     * @throws FileNotFoundException if the file is not found
     */
    suspend fun getFileMetadata(fileId: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            if (!fileStorageService.exists(fileId)) {
                throw FileNotFoundException("File not found: $fileId")
            }
            fileStorageService.getFileMetadata(fileId)
        }

    /**
     * Gets the content of a file.
     *
     * @param fileId The ID of the file to get
     * @return The file content as a list of content objects
     * @throws FileNotFoundException if the file doesn't exist
     */
    suspend fun getFileContent(fileId: String): List<VectorStoreSearchResultContent> =
        withContext(Dispatchers.IO) {
            // Check if the file exists
            if (!fileExists(fileId)) {
                throw FileNotFoundException("File not found: $fileId")
            }

            // Get file content
            val resource = getFileAsResource(fileId)
            val content = resource.inputStream.bufferedReader().use { it.readText() }

            // Return the content wrapped in a VectorStoreSearchResultContent object
            listOf(
                VectorStoreSearchResultContent(
                    type = "text",
                    text = content,
                ),
            )
        }

    /**
     * Gets the file as a resource.
     *
     * @param fileId The ID of the file
     * @return The file as a Resource
     * @throws FileNotFoundException if the file is not found
     */
    suspend fun getFileAsResource(fileId: String): Resource =
        withContext(Dispatchers.IO) {
            if (!fileStorageService.exists(fileId)) {
                throw FileNotFoundException("File not found: $fileId")
            }
            fileStorageService.loadAsResource(fileId)
        }

    /**
     * Asynchronously processes and indexes a file in the vector search provider.
     *
     * @param vectorStoreId The ID of the vector store
     * @param file The vector store file to process
     * @param chunkingStrategy Optional chunking strategy to use when indexing
     */
    fun processFileAsync(
        vectorStoreId: String,
        file: VectorStoreFile,
        chunkingStrategy: ChunkingStrategy? = null,
    ) {
        processingScope.launch {
            try {
                processFile(vectorStoreId, file, chunkingStrategy)
            } catch (e: Exception) {
                log.error("Error processing file ${file.id} in vector store $vectorStoreId", e)
            }
        }
    }

    /**
     * Synchronously processes and indexes a file in the vector search provider.
     *
     * @param vectorStoreId The ID of the vector store
     * @param file The vector store file to process
     * @param chunkingStrategy Optional chunking strategy to use when indexing
     * @return Updated vector store file
     * @throws FileNotFoundException if the file is not found
     * @throws VectorIndexingException if there's an error indexing the file
     */
    suspend fun processFile(
        vectorStoreId: String,
        file: VectorStoreFile,
        chunkingStrategy: ChunkingStrategy? = null,
    ): VectorStoreFile =
        withContext(Dispatchers.IO) {
            // Check if the file still exists in storage
            if (!fileStorageService.exists(file.id)) {
                // Remove the file from the vector store since it no longer exists
                vectorStoreRepository.deleteVectorStoreFile(vectorStoreId, file.id)
                log.error("File ${file.id} no longer exists in storage")
                throw FileNotFoundException("File ${file.id} not found in storage")
            }

            // Get file metadata
            val fileMetadata = fileStorageService.getFileMetadata(file.id)
            val filename = fileMetadata["filename"] as String

            // Index the file in the vector search provider
            log.info("Indexing file ${file.id} in vector store $vectorStoreId")
            try {
                val resource = fileStorageService.loadAsResource(file.id)
                val success = vectorSearchProvider.indexFile(file.id, resource.inputStream, filename, chunkingStrategy, vectorStoreId)
                
                val updatedFile =
                    if (success) {
                        // Update the file status
                        val updated = file.copy(status = "completed")
                        vectorStoreRepository.saveVectorStoreFile(updated)
                        log.info("Indexed file ${file.id} in vector store $vectorStoreId")
                        updated
                    } else {
                        // Update the file status to failed
                        val updated = file.copy(status = "failed")
                        vectorStoreRepository.saveVectorStoreFile(updated)
                        log.error("Failed to index file ${file.id} in vector store $vectorStoreId")
                        throw VectorIndexingException("Failed to index file ${file.id} in vector store $vectorStoreId")
                    }
                
                updatedFile
            } catch (e: Exception) {
                // Update the file status to failed
                val updatedFile = file.copy(status = "failed")
                vectorStoreRepository.saveVectorStoreFile(updatedFile)
                log.error("Error indexing file ${file.id} in vector store $vectorStoreId", e)
                if (e is VectorIndexingException) throw e
                throw VectorIndexingException("Error indexing file ${file.id} in vector store $vectorStoreId", e)
            }
        }

    /**
     * Deletes a file from the vector search provider.
     *
     * @param fileId The ID of the file to delete
     */
    suspend fun deleteFileFromVectorSearch(fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (vectorSearchProvider == null) {
                    log.warn("No vector search provider available, cannot delete file $fileId")
                    return@withContext false
                }
                
                val success = vectorSearchProvider.deleteFile(fileId)
                if (success) {
                    log.info("Deleted file $fileId from vector search provider")
                } else {
                    log.warn("Failed to delete file $fileId from vector search provider")
                }
                success
            } catch (e: Exception) {
                log.error("Error deleting file $fileId from vector search provider", e)
                false
            }
        }
} 
