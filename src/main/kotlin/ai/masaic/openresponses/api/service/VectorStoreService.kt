package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.repository.VectorStoreRepository
import ai.masaic.openresponses.api.support.service.OpenResponsesObsAttributes
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.IdGenerator
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Exception thrown when a vector store is not found.
 */
class VectorStoreNotFoundException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when a vector store file is not found.
 */
class VectorStoreFileNotFoundException(
    message: String,
) : RuntimeException(message)

/**
 * Service for managing vector stores.
 *
 * This service provides methods for creating, retrieving, listing,
 * updating, and deleting vector stores and their files.
 */
@Service
class VectorStoreService(
    @Autowired private val fileStorageService: FileStorageService,
    @Autowired private val vectorStoreRepository: VectorStoreRepository,
    @Autowired(required = false) private val vectorSearchProvider: VectorSearchProvider,
    @Autowired private val telemetryService: TelemetryService,
) {
    private val log = LoggerFactory.getLogger(VectorStoreService::class.java)
    
    // Create a background CoroutineScope for async operations
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Updates the file counts and bytes for a vector store based on the current files.
     *
     * @param vectorStoreId The ID of the vector store to update
     * @return The updated vector store, or null if the vector store doesn't exist
     */
    private suspend fun updateVectorStoreFileCounts(vectorStoreId: String): VectorStore? =
        withContext(Dispatchers.IO) {
            val vectorStore = vectorStoreRepository.findVectorStoreById(vectorStoreId) ?: return@withContext null
        
            // Get all files in the vector store
            val allFiles = vectorStoreRepository.listVectorStoreFiles(vectorStoreId, Int.MAX_VALUE)
        
            // Count files by status
            val inProgress = allFiles.count { it.status == "in_progress" }
            val completed = allFiles.count { it.status == "completed" }
            val failed = allFiles.count { it.status == "failed" }
            val cancelled = allFiles.count { it.status == "cancelled" }
            val total = allFiles.size
        
            // Calculate total bytes
            val bytes = allFiles.sumOf { it.usageBytes }
        
            // Update the vector store
            val updatedVectorStore =
                vectorStore.copy(
                    bytes = bytes,
                    fileCounts =
                        FileCounts(
                            inProgress = inProgress,
                            completed = completed,
                            failed = failed,
                            cancelled = cancelled,
                            total = total,
                        ),
                    lastActiveAt = Instant.now().epochSecond,
                )
        
            vectorStoreRepository.saveVectorStore(updatedVectorStore)
        }

    /**
     * Updates the expiration timestamp for a vector store based on its expiration policy.
     *
     * @param vectorStore The vector store to update
     * @param expirationPolicy The expiration policy to apply
     * @return The updated vector store
     */
    private fun updateExpirationTimestamp(
        vectorStore: VectorStore,
        expirationPolicy: ExpirationPolicy?,
    ): VectorStore {
        if (expirationPolicy == null) {
            return vectorStore.copy(expiresAt = null)
        }

        val expiresAt = expirationPolicy.calculateExpiresAt(vectorStore.lastActiveAt)
        return vectorStore.copy(expiresAt = expiresAt)
    }

    /**
     * Checks if a vector store is expired and updates its status if needed.
     *
     * @param vectorStore The vector store to check
     * @return The updated vector store if it was expired, or the original vector store if not
     */
    private suspend fun checkAndUpdateExpiration(vectorStore: VectorStore): VectorStore =
        withContext(Dispatchers.IO) {
            if (vectorStore.isExpired() && vectorStore.status != "expired") {
                val updatedVectorStore = vectorStore.copy(status = "expired")
                vectorStoreRepository.saveVectorStore(updatedVectorStore)
                updatedVectorStore
            } else {
                vectorStore
            }
        }

    /**
     * Creates a vector store.
     *
     * @param request The request to create a vector store
     * @return The created vector store
     */
    suspend fun createVectorStore(request: CreateVectorStoreRequest): VectorStore =
        withContext(Dispatchers.IO) {
            // Create a new vector store using the IdGenerator utility
            val vectorStoreId = IdGenerator.generateVectorStoreId()
            val createdAt = Instant.now().epochSecond
            
            // Initialize file counts
            var fileCount = 0
            var inProgressCount = 0
            var failedCount = 0
            
            // Set up initial vector store with empty file counts
            val vectorStore =
                VectorStore(
                    id = vectorStoreId,
                    name = request.name,
                    createdAt = createdAt,
                    lastActiveAt = createdAt,
                    metadata = request.metadata,
                    fileCounts =
                        FileCounts(
                            inProgress = 0,
                            completed = 0,
                            failed = 0,
                            cancelled = 0,
                            total = 0,
                        ),
                )
            
            // Apply expiration policy if provided
            val vectorStoreWithExpiration = updateExpirationTimestamp(vectorStore, request.expiresAfter)
            
            // Process file IDs if provided
            var vectorStoreToSave = vectorStoreWithExpiration
            if (!request.fileIds.isNullOrEmpty()) {
                fileCount = request.fileIds.size
                inProgressCount = fileCount // All files start as in_progress
                
                // Update the vector store with initial file counts before processing files
                vectorStoreToSave =
                    vectorStoreWithExpiration.copy(
                        fileCounts =
                            FileCounts(
                                inProgress = inProgressCount,
                                completed = 0,
                                failed = 0,
                                cancelled = 0,
                                total = fileCount,
                            ),
                    )
            }
            
            // Store the vector store with initial counts
            val savedVectorStore = vectorStoreRepository.saveVectorStore(vectorStoreToSave)
        
            // Index any files that were provided
            request.fileIds?.forEach { fileId ->
                try {
                    // Check if the file exists
                    if (!fileStorageService.exists(fileId)) {
                        throw FileNotFoundException("File not found: $fileId")
                    }
                
                    // Get file metadata
                    val fileMetadata = fileStorageService.getFileMetadata(fileId)
                    val filename = fileMetadata["filename"] as String
                    val bytes = fileMetadata["bytes"] as Long
                
                    // Create the vector store file
                    val vectorStoreFile =
                        VectorStoreFile(
                            id = fileId,
                            createdAt = createdAt,
                            usageBytes = bytes,
                            vectorStoreId = vectorStoreId,
                            status = "in_progress",
                            attributes = mapOf("filename" to filename),
                        )
                
                    // Save the vector store file
                    vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
                    
                    // Process the file asynchronously
                    backgroundScope.launch {
                        try {
                            val resource = fileStorageService.loadAsResource(fileId)
                            val indexed =
                                vectorSearchProvider.indexFile(
                                    fileId,
                                    resource.inputStream,
                                    filename,
                                    request.chunkingStrategy,
                                )
                        
                            if (indexed) {
                                // Update the file status
                                val updatedFile = vectorStoreFile.copy(status = "completed")
                                vectorStoreRepository.saveVectorStoreFile(updatedFile)
                                // Update vector store counts
                                updateVectorStoreFileCounts(vectorStoreId)
                            } else {
                                // Update the file status
                                val updatedFile =
                                    vectorStoreFile.copy(
                                        status = "failed",
                                        lastError = "Failed to index file",
                                    )
                                vectorStoreRepository.saveVectorStoreFile(updatedFile)
                                // Update vector store counts
                                updateVectorStoreFileCounts(vectorStoreId)
                            }
                        } catch (e: Exception) {
                            // Update the file status
                            val updatedFile =
                                vectorStoreFile.copy(
                                    status = "failed",
                                    lastError = e.message,
                                )
                            vectorStoreRepository.saveVectorStoreFile(updatedFile)
                            // Update vector store counts
                            updateVectorStoreFileCounts(vectorStoreId)
                            log.error("Error indexing file $fileId", e)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error processing file $fileId", e)
                    // In case of immediate error, update counts directly
                    failedCount++
                    inProgressCount--
                }
            }
            
            // If there were any immediate errors, update the vector store again
            if (failedCount > 0) {
                val updatedVectorStore =
                    savedVectorStore.copy(
                        fileCounts =
                            FileCounts(
                                inProgress = inProgressCount,
                                completed = 0,
                                failed = failedCount,
                                cancelled = 0,
                                total = fileCount,
                            ),
                        status = "completed", // Set to completed if there were errors
                    )
                vectorStoreRepository.saveVectorStore(updatedVectorStore)
                return@withContext updatedVectorStore
            }
            
            // Return the saved vector store (with correct initial counts)
            return@withContext savedVectorStore
        }

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
    ): VectorStoreListResponse =
        withContext(Dispatchers.IO) {
            // Get vector stores from repository
            val vectorStores = vectorStoreRepository.listVectorStores(limit, order, after, before)
            
            // Check and update expiration status for each vector store
            val updatedVectorStores = vectorStores.map { checkAndUpdateExpiration(it) }
        
            // Create response
            VectorStoreListResponse(
                data = updatedVectorStores,
                firstId = updatedVectorStores.firstOrNull()?.id,
                lastId = updatedVectorStores.lastOrNull()?.id,
                hasMore = updatedVectorStores.size >= limit, // If we got exactly the limit, there might be more
            )
        }

    /**
     * Retrieves a vector store.
     *
     * @param vectorStoreId The ID of the vector store to retrieve
     * @return The vector store
     * @throws VectorStoreNotFoundException if the vector store is not found
     */
    suspend fun getVectorStore(vectorStoreId: String): VectorStore =
        withContext(Dispatchers.IO) {
            val vectorStore =
                vectorStoreRepository.findVectorStoreById(vectorStoreId) 
                    ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            
            // Check and update expiration status
            checkAndUpdateExpiration(vectorStore)
        }

    /**
     * Updates a vector store.
     *
     * @param vectorStoreId The ID of the vector store to update
     * @param request The request to update the vector store
     * @return The updated vector store
     * @throws VectorStoreNotFoundException if the vector store is not found
     */
    suspend fun updateVectorStore(
        vectorStoreId: String,
        request: ModifyVectorStoreRequest,
    ): VectorStore =
        withContext(Dispatchers.IO) {
            val vectorStore =
                vectorStoreRepository.findVectorStoreById(vectorStoreId) 
                    ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
        
            // Update the vector store
            val updatedVectorStore =
                vectorStore.copy(
                    name = request.name ?: vectorStore.name,
                    metadata = request.metadata ?: vectorStore.metadata,
                    lastActiveAt = Instant.now().epochSecond,
                )
        
            // Apply expiration policy if provided
            val vectorStoreWithExpiration = updateExpirationTimestamp(updatedVectorStore, request.expiresAfter)
        
            vectorStoreRepository.saveVectorStore(vectorStoreWithExpiration)
        }

    /**
     * Deletes a vector store.
     *
     * @param vectorStoreId The ID of the vector store to delete
     * @return The delete response
     * @throws VectorStoreNotFoundException if the vector store is not found
     */
    suspend fun deleteVectorStore(vectorStoreId: String): VectorStoreDeleteResponse =
        withContext(Dispatchers.IO) {
            val vectorStore =
                vectorStoreRepository.findVectorStoreById(vectorStoreId)
                    ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
        
            // Get all files in the vector store
            val files = vectorStoreRepository.listVectorStoreFiles(vectorStoreId, Int.MAX_VALUE)
        
            // Delete all files from the vector search provider
            files.forEach { file ->
                try {
                    vectorSearchProvider.deleteFile(file.id)
                } catch (e: Exception) {
                    log.error("Error deleting file ${file.id} from vector search provider", e)
                }
            }
        
            // Delete the vector store from the repository
            val deleted = vectorStoreRepository.deleteVectorStore(vectorStoreId)
        
            VectorStoreDeleteResponse(id = vectorStoreId, deleted = deleted)
        }

    /**
     * Creates a file in a vector store.
     *
     * @param vectorStoreId The ID of the vector store
     * @param request The request to create a file
     * @return The created vector store file
     * @throws VectorStoreNotFoundException if the vector store is not found
     * @throws FileNotFoundException if the file is not found
     */
    suspend fun createVectorStoreFile(
        vectorStoreId: String,
        request: CreateVectorStoreFileRequest,
    ): VectorStoreFile =
        withContext(Dispatchers.IO) {
            // Use telemetry to track the vector store file creation
            telemetryService.withVectorStoreOperation(
                operationName = "create_file",
                vectorStoreId = vectorStoreId,
            ) {
                // Check if the vector store exists
                val vectorStore =
                    vectorStoreRepository.findVectorStoreById(vectorStoreId)
                        ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            
                // Check if the file exists
                val fileId = request.fileId
                if (!fileStorageService.exists(fileId)) {
                    throw FileNotFoundException("File not found: $fileId")
                }
            
                // Get file metadata
                val fileMetadata = fileStorageService.getFileMetadata(fileId)
                val filename = fileMetadata["filename"] as String
                val bytes = fileMetadata["bytes"] as Long
            
                // Create the vector store file - merge attributes with filename
                val attributes = request.attributes?.toMutableMap() ?: mutableMapOf()
                attributes["filename"] = filename
                
                val vectorStoreFile =
                    VectorStoreFile(
                        id = fileId,
                        createdAt = Instant.now().epochSecond,
                        usageBytes = bytes,
                        vectorStoreId = vectorStoreId,
                        status = "in_progress",
                        attributes = attributes,
                        chunkingStrategy = request.chunkingStrategy,
                    )
            
                // Save the vector store file
                val savedFile = vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
                
                // Update the vector store counts
                updateVectorStoreFileCounts(vectorStoreId)
                
                // Process the file asynchronously
                backgroundScope.launch {
                    try {
                        val resource = fileStorageService.loadAsResource(fileId)
                        val indexed =
                            vectorSearchProvider.indexFile(
                                fileId,
                                resource.inputStream,
                                filename,
                                vectorStoreFile.chunkingStrategy,
                            )
                    
                        if (indexed) {
                            // Update the file status
                            val updatedFile = vectorStoreFile.copy(status = "completed")
                            vectorStoreRepository.saveVectorStoreFile(updatedFile)
                            // Update vector store counts
                            updateVectorStoreFileCounts(vectorStoreId)
                        } else {
                            // Update the file status
                            val updatedFile =
                                vectorStoreFile.copy(
                                    status = "failed",
                                    lastError = "Failed to index file",
                                )
                            vectorStoreRepository.saveVectorStoreFile(updatedFile)
                            // Update vector store counts
                            updateVectorStoreFileCounts(vectorStoreId)
                        }
                    } catch (e: Exception) {
                        // Update the file status
                        val updatedFile =
                            vectorStoreFile.copy(
                                status = "failed",
                                lastError = e.message,
                            )
                        vectorStoreRepository.saveVectorStoreFile(updatedFile)
                        // Update vector store counts
                        updateVectorStoreFileCounts(vectorStoreId)
                        log.error("Error indexing file $fileId", e)
                    }
                }
                
                // Return the saved file
                savedFile
            }
        }

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
     * @throws VectorStoreNotFoundException if the vector store is not found
     */
    suspend fun listVectorStoreFiles(
        vectorStoreId: String,
        limit: Int = 20,
        order: String = "desc",
        after: String? = null,
        before: String? = null,
        filter: String? = null,
    ): VectorStoreFileListResponse =
        withContext(Dispatchers.IO) {
            // Check if the vector store exists
            if (vectorStoreRepository.findVectorStoreById(vectorStoreId) == null) {
                throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            }
        
            // Get all files in the vector store from repository
            val files = vectorStoreRepository.listVectorStoreFiles(vectorStoreId, limit, order, after, before, filter)
        
            // Create response
            VectorStoreFileListResponse(
                data = files,
                firstId = files.firstOrNull()?.id,
                lastId = files.lastOrNull()?.id,
                hasMore = files.size >= limit, // If we got exactly the limit, there might be more
            )
        }

    /**
     * Retrieves a vector store file.
     *
     * @param vectorStoreId The ID of the vector store
     * @param fileId The ID of the file
     * @return The vector store file
     * @throws VectorStoreNotFoundException if the vector store is not found
     * @throws VectorStoreFileNotFoundException if the file is not found in the vector store
     * @throws FileNotFoundException if the file no longer exists in storage
     */
    suspend fun getVectorStoreFile(
        vectorStoreId: String,
        fileId: String,
    ): VectorStoreFile =
        withContext(Dispatchers.IO) {
            // Check if the vector store exists
            if (vectorStoreRepository.findVectorStoreById(vectorStoreId) == null) {
                throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            }
        
            // Get the file from repository
            val file =
                vectorStoreRepository.findVectorStoreFileById(vectorStoreId, fileId)
                    ?: throw VectorStoreFileNotFoundException("File not found in vector store: $fileId")
        
            // Check if the file still exists in storage
            if (!fileStorageService.exists(fileId)) {
                // Remove the file from the vector store since it no longer exists
                vectorStoreRepository.deleteVectorStoreFile(vectorStoreId, fileId)
                vectorSearchProvider?.deleteFile(fileId)
                updateVectorStoreFileCounts(vectorStoreId)
            
                throw FileNotFoundException("File $fileId referenced in vector store $vectorStoreId no longer exists in storage")
            }
        
            file
        }

    /**
     * Updates a vector store file's attributes.
     *
     * @param vectorStoreId The ID of the vector store
     * @param fileId The ID of the file
     * @param request The request to update the file's attributes
     * @return The updated vector store file
     * @throws VectorStoreNotFoundException if the vector store is not found
     * @throws VectorStoreFileNotFoundException if the file is not found in the vector store
     * @throws FileNotFoundException if the file no longer exists in storage
     */
    suspend fun updateVectorStoreFileAttributes(
        vectorStoreId: String,
        fileId: String,
        request: UpdateVectorStoreFileAttributesRequest,
    ): VectorStoreFile =
        withContext(Dispatchers.IO) {
            // Check if the vector store exists
            if (vectorStoreRepository.findVectorStoreById(vectorStoreId) == null) {
                throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            }
        
            // Get the file from repository
            val file =
                vectorStoreRepository.findVectorStoreFileById(vectorStoreId, fileId)
                    ?: throw VectorStoreFileNotFoundException("File not found in vector store: $fileId")
        
            // Check if the file still exists in storage
            if (!fileStorageService.exists(fileId)) {
                // Remove the file from the vector store since it no longer exists
                vectorStoreRepository.deleteVectorStoreFile(vectorStoreId, fileId)
                vectorSearchProvider.deleteFile(fileId)
                updateVectorStoreFileCounts(vectorStoreId)
            
                throw FileNotFoundException("File $fileId referenced in vector store $vectorStoreId no longer exists in storage")
            }
        
            // Update the file attributes
            val updatedFile = file.copy(attributes = request.attributes)
        
            // Save the updated file
            vectorStoreRepository.saveVectorStoreFile(updatedFile)
        }

    /**
     * Deletes a vector store file.
     *
     * @param vectorStoreId The ID of the vector store
     * @param fileId The ID of the file
     * @return The delete response
     * @throws VectorStoreNotFoundException if the vector store is not found
     * @throws VectorStoreFileNotFoundException if the file is not found in the vector store
     */
    suspend fun deleteVectorStoreFile(
        vectorStoreId: String,
        fileId: String,
    ): VectorStoreFileDeleteResponse =
        withContext(Dispatchers.IO) {
            // Check if the vector store exists
            val vectorStore =
                vectorStoreRepository.findVectorStoreById(vectorStoreId)
                    ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
        
            // Get the file from repository
            val file =
                vectorStoreRepository.findVectorStoreFileById(vectorStoreId, fileId)
                    ?: throw VectorStoreFileNotFoundException("File not found in vector store: $fileId")
        
            // Delete the file from the repository
            val deleted = vectorStoreRepository.deleteVectorStoreFile(vectorStoreId, fileId)
        
            // Delete the file from the vector search provider
            try {
                vectorSearchProvider.deleteFile(fileId)
            } catch (e: Exception) {
                log.error("Error deleting file $fileId from vector search provider", e)
            }
        
            // Update vector store counts 
            updateVectorStoreFileCounts(vectorStoreId)
        
            VectorStoreFileDeleteResponse(id = fileId, deleted = deleted)
        }

    /**
     * Searches a vector store for relevant chunks based on a query.
     *
     * @param vectorStoreId The ID of the vector store
     * @param request The search request
     * @return The search results
     * @throws VectorStoreNotFoundException if the vector store is not found
     */
    suspend fun searchVectorStore(
        vectorStoreId: String,
        request: VectorStoreSearchRequest,
    ): VectorStoreSearchResults =
        withContext(Dispatchers.IO) {
            // Create observation for search telemetry
            val observation =
                telemetryService.startSearchOperation(
                    operationName = "vector_store_search",
                    vectorStoreId = vectorStoreId,
                    query = request.query,
                )
            
            // Start timing the operation
            val timerBuilder =
                Timer
                    .builder(OpenResponsesObsAttributes.SEARCH_DURATION)
                    .description("Search operation duration")
                    .tags(OpenResponsesObsAttributes.SEARCH_OPERATION, "vector_store_search")
                    .tag(OpenResponsesObsAttributes.VECTOR_STORE_ID, vectorStoreId)
            
            val timer = timerBuilder.register(telemetryService.meterRegistry)
            val sample = Timer.start(telemetryService.meterRegistry)
            
            try {
                // Check if the vector store exists
                val vectorStore =
                    vectorStoreRepository.findVectorStoreById(vectorStoreId)
                        ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            
                // Get all files in the vector store
                val files = vectorStoreRepository.listVectorStoreFiles(vectorStoreId, Int.MAX_VALUE)
            
                // Filter out files that no longer exist in the FileStorageService
                val existingFiles =
                    files.filter { file ->
                        try {
                            fileStorageService.exists(file.id)
                        } catch (e: Exception) {
                            log.warn("File ${file.id} referenced in vector store $vectorStoreId no longer exists in storage")
                            false
                        }
                    }
            
                // Check if any files were removed and update the vector store if needed
                if (existingFiles.size < files.size) {
                    log.info("Detected ${files.size - existingFiles.size} missing files in vector store $vectorStoreId, updating...")
                
                    // Remove the missing files from the vector store
                    val missingFiles = files.filter { file -> !existingFiles.any { it.id == file.id } }
                    missingFiles.forEach { file ->
                        try {
                            vectorStoreRepository.deleteVectorStoreFile(vectorStoreId, file.id)
                            vectorSearchProvider.deleteFile(file.id)
                        } catch (e: Exception) {
                            log.error("Error removing missing file ${file.id} from vector store $vectorStoreId", e)
                        }
                    }
                
                    // Update the vector store
                    updateVectorStoreFileCounts(vectorStoreId)
                }
            
                val fileIds = existingFiles.map { it.id }
                if (fileIds.isEmpty()) {
                    // Return empty results if there are no files
                    telemetryService.stopSearchOperation(
                        observation,
                        resultsCount = 0,
                        success = true,
                    )
                    sample.stop(timer)
                    return@withContext VectorStoreSearchResults(
                        searchQuery = request.query,
                        data = emptyList(),
                    )
                }
            
                // Add file filter to the request filters
                val filters = mutableMapOf<String, Any>()
                request.filters?.let { filters.putAll(it) }
                
                // Add fileIds filter (list of file IDs to search within)
                // This supports both implementations - newer ones handle 'fileIds' as a list,
                // while older ones might look for individual 'fileId' matches
                filters["fileIds"] = fileIds
                
                // Execute the vector search
                val maxResults = request.maxNumResults ?: 10
                val searchResults = vectorSearchProvider.searchSimilar(request.query, maxResults, filters)
                
                // Update the vector store's last active timestamp
                val updatedVectorStore = vectorStore.copy(lastActiveAt = Instant.now().epochSecond)
                vectorStoreRepository.saveVectorStore(updatedVectorStore)
                
                // Convert search results to VectorStoreSearchResult objects
                val results =
                    searchResults.mapIndexed { index, result ->
                        val fileId = result.fileId
                        val file = existingFiles.find { it.id == fileId }
                        
                        // Get the filename or fall back to file id if not found
                        val filename = 
                            file?.attributes?.get("filename") as? String
                                ?: fileStorageService.getFileMetadata(fileId)["filename"] as? String 
                                ?: fileId
                        
                        // Extract chunk_id from result metadata if available
                        val chunkId = result.metadata["chunk_id"] as? String
                        
                        // Combine file attributes with chunk-specific metadata
                        val combinedAttributes = mutableMapOf<String, Any>()
                        file?.attributes?.let { combinedAttributes.putAll(it) }
                        
                        // Add chunk_id to attributes if available
                        if (chunkId != null) {
                            combinedAttributes["chunk_id"] = chunkId
                        }
                    
                        VectorStoreSearchResult(
                            fileId = fileId,
                            filename = filename,
                            score = result.score,
                            attributes = combinedAttributes,
                            content =
                                listOf(
                                    VectorStoreSearchResultContent(
                                        type = "text",
                                        text = result.content,
                                    ),
                                ),
                        )
                    }
                
                // Extract documents and chunks information for telemetry
                val documentIds = results.map { it.fileId }.distinct()
                // Extract chunk IDs directly from search result metadata
                val chunkIds =
                    searchResults.mapNotNull { result -> 
                        (result.metadata["chunk_id"] ?: "").toString().takeIf { it.isNotEmpty() }
                    }
                // Extract scores for telemetry
                val scores = results.map { it.score }
                
                // Create the search results response with document and chunk information
                val searchResultsOutput =
                    VectorStoreSearchResults(
                        searchQuery = request.query,
                        data = results,
                    )
                
                // Record detailed search metrics
                telemetryService.stopSearchOperation(
                    observation,
                    resultsCount = results.size,
                    documentIds = documentIds,
                    chunkIds = chunkIds,
                    scores = scores,
                    success = true,
                )
                
                searchResultsOutput
            } catch (e: Exception) {
                telemetryService.stopSearchOperation(observation, success = false)
                observation.error(e)
                throw e
            } finally {
                sample.stop(timer)
            }
        }

    /**
     * Gets the content of a vector store file.
     *
     * @param vectorStoreId The ID of the vector store
     * @param fileId The ID of the file
     * @return The file content
     * @throws VectorStoreNotFoundException if the vector store is not found
     * @throws VectorStoreFileNotFoundException if the file is not found in the vector store
     * @throws FileNotFoundException if the file no longer exists in storage
     */
    suspend fun getVectorStoreFileContent(
        vectorStoreId: String,
        fileId: String,
    ): VectorStoreFileContent =
        withContext(Dispatchers.IO) {
            // Check if the vector store exists
            val vectorStore =
                vectorStoreRepository.findVectorStoreById(vectorStoreId)
                    ?: throw VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
        
            // Get the file from repository
            val file =
                vectorStoreRepository.findVectorStoreFileById(vectorStoreId, fileId)
                    ?: throw VectorStoreFileNotFoundException("File not found in vector store: $fileId")
        
            // Check if the file still exists in storage
            if (!fileStorageService.exists(fileId)) {
                // Remove the file from the vector store since it no longer exists
                vectorStoreRepository.deleteVectorStoreFile(vectorStoreId, fileId)
                vectorSearchProvider.deleteFile(fileId)
                updateVectorStoreFileCounts(vectorStoreId)
            
                throw FileNotFoundException("File $fileId referenced in vector store $vectorStoreId no longer exists in storage")
            }
        
            // Get file content
            val resource = fileStorageService.loadAsResource(fileId)
            val content = resource.inputStream.bufferedReader().use { it.readText() }
        
            // Update the vector store's last active timestamp
            val updatedVectorStore = vectorStore.copy(lastActiveAt = Instant.now().epochSecond)
            vectorStoreRepository.saveVectorStore(updatedVectorStore)

            // Get filename from file attributes or fall back to file id if not found
            val filename =
                file.attributes?.get("filename") as? String 
                    ?: fileStorageService.getFileMetadata(fileId)["filename"] as? String
                    ?: fileId
        
            VectorStoreFileContent(
                fileId = fileId,
                filename = filename,
                attributes = file.attributes,
                content =
                    listOf(
                        VectorStoreSearchResultContent(
                            type = "text",
                            text = content,
                        ),
                    ),
            )
        }

    /**
     * Cleans up vector stores by removing references to files that no longer exist in storage.
     * This method can be called periodically to ensure consistency between vector stores and file storage.
     *
     * @return The number of files removed from vector stores
     */
    suspend fun cleanupVectorStores(): Int =
        withContext(Dispatchers.IO) {
            var removedFilesCount = 0
        
            try {
                // Get all vector stores
                val vectorStores = vectorStoreRepository.listVectorStores(Int.MAX_VALUE)
            
                // Process each vector store
                vectorStores.forEach { vectorStore ->
                    // Get all files in the vector store
                    val files = vectorStoreRepository.listVectorStoreFiles(vectorStore.id, Int.MAX_VALUE)
                
                    // Check each file to see if it still exists in storage
                    val missingFiles =
                        files.filter { file ->
                            try {
                                !fileStorageService.exists(file.id)
                            } catch (e: Exception) {
                                log.warn("Error checking existence of file ${file.id}", e)
                                true // Consider as missing if there's an error
                            }
                        }
                
                    // Remove missing files from the vector store
                    missingFiles.forEach { file ->
                        try {
                            vectorStoreRepository.deleteVectorStoreFile(vectorStore.id, file.id)
                            vectorSearchProvider.deleteFile(file.id)
                            removedFilesCount++
                            log.info("Removed reference to missing file ${file.id} from vector store ${vectorStore.id}")
                        } catch (e: Exception) {
                            log.error("Error removing file ${file.id} from vector store ${vectorStore.id}", e)
                        }
                    }
                
                    // Update vector store counts if files were removed
                    if (missingFiles.isNotEmpty()) {
                        updateVectorStoreFileCounts(vectorStore.id)
                    }
                }
            
                log.info("Vector store cleanup completed: removed $removedFilesCount file references")
            } catch (e: Exception) {
                log.error("Error during vector store cleanup", e)
            }
        
            removedFilesCount
        }

    /**
     * Cleans up expired vector stores.
     *
     * @return The number of expired vector stores that were cleaned up
     */
    suspend fun cleanupExpiredVectorStores(): Int =
        withContext(Dispatchers.IO) {
            var cleanedUpCount = 0
            
            try {
                // Get all vector stores
                val vectorStores = vectorStoreRepository.listVectorStores(Int.MAX_VALUE)
                
                // Process each vector store
                vectorStores.forEach { vectorStore ->
                    // Check if the vector store is expired
                    if (vectorStore.isExpired() && vectorStore.status != "expired") {
                        try {
                            // Update the vector store status to expired
                            val updatedVectorStore = vectorStore.copy(status = "expired")
                            vectorStoreRepository.saveVectorStore(updatedVectorStore)
                            cleanedUpCount++
                            log.info("Marked vector store ${vectorStore.id} as expired")
                        } catch (e: Exception) {
                            log.error("Error marking vector store ${vectorStore.id} as expired", e)
                        }
                    }
                }
                
                log.info("Vector store expiration cleanup completed: marked $cleanedUpCount vector stores as expired")
            } catch (e: Exception) {
                log.error("Error during vector store expiration cleanup", e)
            }
            
            cleanedUpCount
        }
} 
