package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.exception.VectorStoreFileNotFoundException
import ai.masaic.openresponses.api.exception.VectorStoreNotFoundException
import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.api.service.storage.FileNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for the Vector Stores API.
 *
 * This controller handles endpoints for managing vector stores.
 */
@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
class VectorStoreController(
    private val vectorStoreService: VectorStoreService,
) {
    private val log = LoggerFactory.getLogger(VectorStoreController::class.java)

    @PostMapping("/vector_stores")
    suspend fun createVectorStore(
        @RequestBody request: CreateVectorStoreRequest,
    ): ResponseEntity<VectorStore> {
        try {
            log.info("Creating vector store: ${request.name}")
            val vectorStore = vectorStoreService.createVectorStore(request)
            return ResponseEntity.ok(vectorStore)
        } catch (e: IllegalStateException) {
            log.error("Error creating vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: FileNotFoundException) {
            log.error("Error creating vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error creating vector store", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error creating vector store: ${e.message}")
        }
    }

    @GetMapping("/vector_stores")
    suspend fun listVectorStores(
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) before: String?,
    ): ResponseEntity<VectorStoreListResponse> {
        try {
            val response = vectorStoreService.listVectorStores(limit, order, after, before)
            return ResponseEntity.ok(response)
        } catch (e: Exception) {
            log.error("Error listing vector stores", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error listing vector stores: ${e.message}")
        }
    }

    @GetMapping("/vector_stores/{vector_store_id}")
    suspend fun getVectorStore(
        @PathVariable("vector_store_id") vectorStoreId: String,
    ): ResponseEntity<VectorStore> {
        try {
            val vectorStore = vectorStoreService.getVectorStore(vectorStoreId)
            return ResponseEntity.ok(vectorStore)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error retrieving vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error retrieving vector store", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving vector store: ${e.message}")
        }
    }

    @PostMapping("/vector_stores/{vector_store_id}")
    suspend fun updateVectorStore(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @RequestBody request: ModifyVectorStoreRequest,
    ): ResponseEntity<VectorStore> {
        try {
            val vectorStore = vectorStoreService.updateVectorStore(vectorStoreId, request)
            return ResponseEntity.ok(vectorStore)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error updating vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error updating vector store", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error updating vector store: ${e.message}")
        }
    }

    @DeleteMapping("/vector_stores/{vector_store_id}")
    suspend fun deleteVectorStore(
        @PathVariable("vector_store_id") vectorStoreId: String,
    ): ResponseEntity<VectorStoreDeleteResponse> {
        try {
            val response = vectorStoreService.deleteVectorStore(vectorStoreId)
            return ResponseEntity.ok(response)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error deleting vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error deleting vector store", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error deleting vector store: ${e.message}")
        }
    }

    @PostMapping("/vector_stores/{vector_store_id}/search")
    suspend fun searchVectorStore(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @RequestBody request: VectorStoreSearchRequest,
        @RequestHeader("Authorization") authHeader: String?,
    ): ResponseEntity<VectorStoreSearchResults> {
        try {
            val updatedRequest = request.copy(modelInfo = ModelInfo.fromApiKey(authHeader, request.modelInfo?.model))
            val results = vectorStoreService.searchVectorStore(vectorStoreId, updatedRequest)
            return ResponseEntity.ok(results)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error searching vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            log.error("Error searching vector store: ${e.message}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: Exception) {
            log.error("Error searching vector store", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error searching vector store: ${e.message}")
        }
    }

    @PostMapping("/vector_stores/{vector_store_id}/files")
    suspend fun createVectorStoreFile(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @RequestBody request: CreateVectorStoreFileRequest,
        @RequestHeader("Authorization") authHeader: String? = null,
    ): ResponseEntity<VectorStoreFile> {
        try {
            val updatedRequest = request.copy(modelInfo = ModelInfo.fromApiKey(authHeader, request.modelInfo?.model))
            val vectorStoreFile = vectorStoreService.createVectorStoreFile(vectorStoreId, updatedRequest)
            return ResponseEntity.ok(vectorStoreFile)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error creating vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: FileNotFoundException) {
            log.error("Error creating vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            log.error("Error creating vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: Exception) {
            log.error("Error creating vector store file", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error creating vector store file: ${e.message}",
            )
        }
    }

    @GetMapping("/vector_stores/{vector_store_id}/files")
    suspend fun listVectorStoreFiles(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<VectorStoreFileListResponse> {
        try {
            val response =
                vectorStoreService.listVectorStoreFiles(
                    vectorStoreId,
                    limit,
                    order,
                    after,
                    before,
                    filter,
                )
            return ResponseEntity.ok(response)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error listing vector store files: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error listing vector store files", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error listing vector store files: ${e.message}",
            )
        }
    }

    @GetMapping("/vector_stores/{vector_store_id}/files/{file_id}")
    suspend fun getVectorStoreFile(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @PathVariable("file_id") fileId: String,
    ): ResponseEntity<VectorStoreFile> {
        try {
            val vectorStoreFile = vectorStoreService.getVectorStoreFile(vectorStoreId, fileId)
            return ResponseEntity.ok(vectorStoreFile)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error retrieving vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: VectorStoreFileNotFoundException) {
            log.error("Error retrieving vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error retrieving vector store file", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error retrieving vector store file: ${e.message}",
            )
        }
    }

    @GetMapping("/vector_stores/{vector_store_id}/files/{file_id}/content")
    suspend fun getVectorStoreFileContent(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @PathVariable("file_id") fileId: String,
    ): ResponseEntity<VectorStoreFileContent> {
        try {
            val fileContent = vectorStoreService.getVectorStoreFileContent(vectorStoreId, fileId)
            return ResponseEntity.ok(fileContent)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error retrieving vector store file content: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: VectorStoreFileNotFoundException) {
            log.error("Error retrieving vector store file content: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error retrieving vector store file content", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error retrieving vector store file content: ${e.message}",
            )
        }
    }

    @PostMapping("/vector_stores/{vector_store_id}/files/{file_id}")
    suspend fun updateVectorStoreFileAttributes(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @PathVariable("file_id") fileId: String,
        @RequestBody request: UpdateVectorStoreFileAttributesRequest,
    ): ResponseEntity<VectorStoreFile> {
        try {
            val vectorStoreFile =
                vectorStoreService.updateVectorStoreFileAttributes(
                    vectorStoreId,
                    fileId,
                    request,
                )
            return ResponseEntity.ok(vectorStoreFile)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error updating vector store file attributes: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: VectorStoreFileNotFoundException) {
            log.error("Error updating vector store file attributes: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error updating vector store file attributes", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error updating vector store file attributes: ${e.message}",
            )
        }
    }

    @DeleteMapping("/vector_stores/{vector_store_id}/files/{file_id}")
    suspend fun deleteVectorStoreFile(
        @PathVariable("vector_store_id") vectorStoreId: String,
        @PathVariable("file_id") fileId: String,
    ): ResponseEntity<VectorStoreFileDeleteResponse> {
        try {
            val response = vectorStoreService.deleteVectorStoreFile(vectorStoreId, fileId)
            return ResponseEntity.ok(response)
        } catch (e: VectorStoreNotFoundException) {
            log.error("Error deleting vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: VectorStoreFileNotFoundException) {
            log.error("Error deleting vector store file: ${e.message}")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: Exception) {
            log.error("Error deleting vector store file", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error deleting vector store file: ${e.message}",
            )
        }
    }
} 
