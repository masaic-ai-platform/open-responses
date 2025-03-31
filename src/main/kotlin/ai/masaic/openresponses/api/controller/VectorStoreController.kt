package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.FileNotFoundException
import ai.masaic.openresponses.api.service.VectorStoreFileNotFoundException
import ai.masaic.openresponses.api.service.VectorStoreNotFoundException
import ai.masaic.openresponses.api.service.VectorStoreService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Vector Stores", description = "Vector Store API")
class VectorStoreController(
    private val vectorStoreService: VectorStoreService,
) {
    private val log = LoggerFactory.getLogger(VectorStoreController::class.java)

    @PostMapping("/vector_stores")
    @Operation(
        summary = "Create a vector store",
        description = "Create a vector store.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The created vector store",
                content = [Content(schema = Schema(implementation = VectorStore::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "Bad request",
            ),
        ],
    )
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
    @Operation(
        summary = "List vector stores",
        description = "Returns a list of vector stores.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "List of vector stores",
                content = [Content(schema = Schema(implementation = VectorStoreListResponse::class))],
            ),
        ],
    )
    suspend fun listVectorStores(
        @Parameter(description = "A limit on the number of objects to be returned. Limit can range between 1 and 100, and the default is 20.")
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @Parameter(description = "Sort order by the created_at timestamp of the objects. asc for ascending order and desc for descending order.")
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @Parameter(description = "A cursor for use in pagination. after is an object ID that defines your place in the list.")
        @RequestParam(required = false) after: String?,
        @Parameter(description = "A cursor for use in pagination. before is an object ID that defines your place in the list.")
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
    @Operation(
        summary = "Retrieve a vector store",
        description = "Retrieves a vector store.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The vector store",
                content = [Content(schema = Schema(implementation = VectorStore::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store not found",
            ),
        ],
    )
    suspend fun getVectorStore(
        @Parameter(description = "The ID of the vector store to retrieve", required = true)
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
    @Operation(
        summary = "Modify a vector store",
        description = "Modifies a vector store.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The modified vector store",
                content = [Content(schema = Schema(implementation = VectorStore::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store not found",
            ),
        ],
    )
    suspend fun updateVectorStore(
        @Parameter(description = "The ID of the vector store to modify", required = true)
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
    @Operation(
        summary = "Delete a vector store",
        description = "Delete a vector store.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Deletion status",
                content = [Content(schema = Schema(implementation = VectorStoreDeleteResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store not found",
            ),
        ],
    )
    suspend fun deleteVectorStore(
        @Parameter(description = "The ID of the vector store to delete", required = true)
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
    @Operation(
        summary = "Search a vector store",
        description = "Search a vector store for relevant chunks based on a query and file attributes filter.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Search results",
                content = [Content(schema = Schema(implementation = VectorStoreSearchResults::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store not found",
            ),
        ],
    )
    suspend fun searchVectorStore(
        @Parameter(description = "The ID of the vector store to search", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @RequestBody request: VectorStoreSearchRequest,
    ): ResponseEntity<VectorStoreSearchResults> {
        try {
            val results = vectorStoreService.searchVectorStore(vectorStoreId, request)
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
    @Operation(
        summary = "Create a vector store file",
        description = "Create a vector store file by attaching a File to a vector store.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The created vector store file",
                content = [Content(schema = Schema(implementation = VectorStoreFile::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store or file not found",
            ),
        ],
    )
    suspend fun createVectorStoreFile(
        @Parameter(description = "The ID of the vector store", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @RequestBody request: CreateVectorStoreFileRequest,
    ): ResponseEntity<VectorStoreFile> {
        try {
            val vectorStoreFile = vectorStoreService.createVectorStoreFile(vectorStoreId, request)
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
    @Operation(
        summary = "List vector store files",
        description = "Returns a list of vector store files.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "List of vector store files",
                content = [Content(schema = Schema(implementation = VectorStoreFileListResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store not found",
            ),
        ],
    )
    suspend fun listVectorStoreFiles(
        @Parameter(description = "The ID of the vector store", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @Parameter(description = "A limit on the number of objects to be returned. Limit can range between 1 and 100, and the default is 20.")
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @Parameter(description = "Sort order by the created_at timestamp of the objects. asc for ascending order and desc for descending order.")
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @Parameter(description = "A cursor for use in pagination. after is an object ID that defines your place in the list.")
        @RequestParam(required = false) after: String?,
        @Parameter(description = "A cursor for use in pagination. before is an object ID that defines your place in the list.")
        @RequestParam(required = false) before: String?,
        @Parameter(description = "Filter by file status. One of in_progress, completed, failed, cancelled.")
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
    @Operation(
        summary = "Retrieve a vector store file",
        description = "Retrieves a vector store file.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The vector store file",
                content = [Content(schema = Schema(implementation = VectorStoreFile::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store or file not found",
            ),
        ],
    )
    suspend fun getVectorStoreFile(
        @Parameter(description = "The ID of the vector store", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @Parameter(description = "The ID of the file", required = true)
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
    @Operation(
        summary = "Retrieve vector store file content",
        description = "Retrieve the parsed contents of a vector store file.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The parsed contents of the vector store file",
                content = [Content(schema = Schema(implementation = VectorStoreFileContent::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store or file not found",
            ),
        ],
    )
    suspend fun getVectorStoreFileContent(
        @Parameter(description = "The ID of the vector store", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @Parameter(description = "The ID of the file", required = true)
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
    @Operation(
        summary = "Update vector store file attributes",
        description = "Update attributes on a vector store file.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The updated vector store file",
                content = [Content(schema = Schema(implementation = VectorStoreFile::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store or file not found",
            ),
        ],
    )
    suspend fun updateVectorStoreFileAttributes(
        @Parameter(description = "The ID of the vector store", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @Parameter(description = "The ID of the file", required = true)
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
    @Operation(
        summary = "Delete vector store file",
        description = "Delete a vector store file. This will remove the file from the vector store but the file itself will not be deleted.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Deletion status",
                content = [Content(schema = Schema(implementation = VectorStoreFileDeleteResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Vector store or file not found",
            ),
        ],
    )
    suspend fun deleteVectorStoreFile(
        @Parameter(description = "The ID of the vector store", required = true)
        @PathVariable("vector_store_id") vectorStoreId: String,
        @Parameter(description = "The ID of the file", required = true)
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
