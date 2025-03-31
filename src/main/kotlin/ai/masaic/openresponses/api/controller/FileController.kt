package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.model.FileDeleteResponse
import ai.masaic.openresponses.api.model.FileListResponse
import ai.masaic.openresponses.api.service.FileNotFoundException
import ai.masaic.openresponses.api.service.FileService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for the Files API.
 *
 * This controller handles endpoints for uploading, listing, retrieving, and deleting files.
 */
@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
@Tag(name = "Files", description = "OpenAI File API")
class FileController(
    private val fileService: FileService,
) {
    private val log = LoggerFactory.getLogger(FileController::class.java)

    @PostMapping("/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Upload a file",
        description = "Upload a file that can be used across various endpoints. Individual files can be up to 512 MB.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The uploaded File object",
                content = [Content(schema = Schema(implementation = File::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "Bad request, such as invalid purpose or file too large",
            ),
        ],
    )
    suspend fun uploadFile(
        @Parameter(description = "The File object (not file name) to be uploaded", required = true)
        @RequestParam("file") file: MultipartFile,
        @Parameter(
            description =
                "The intended purpose of the uploaded file. " +
                    "One of: assistants, batch, fine-tune, vision, user_data, evals",
            required = true,
        )
        @RequestParam("purpose") purpose: String,
    ): ResponseEntity<File> {
        try {
            log.info("Uploading file: ${file.originalFilename} for purpose: $purpose")
            val uploadedFile = fileService.uploadFile(file, purpose)
            return ResponseEntity.ok(uploadedFile)
        } catch (e: IllegalArgumentException) {
            log.error("Error uploading file: ${e.message}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: Exception) {
            log.error("Error uploading file", e)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error uploading file: ${e.message}")
        }
    }

    @GetMapping("/files")
    @Operation(
        summary = "List files",
        description = "Returns a list of files based on the filters provided.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A list of File objects",
                content = [Content(schema = Schema(implementation = FileListResponse::class))],
            ),
        ],
    )
    suspend fun listFiles(
        @Parameter(description = "A cursor for use in pagination")
        @RequestParam(required = false) after: String?,
        @Parameter(description = "A limit on the number of objects to be returned (1-10000)")
        @RequestParam(defaultValue = "10000") limit: Int,
        @Parameter(description = "Sort order by creation timestamp (asc or desc)")
        @RequestParam(defaultValue = "desc") order: String,
        @Parameter(description = "Only return files with the given purpose")
        @RequestParam(required = false) purpose: String?,
    ): ResponseEntity<FileListResponse> {
        val validLimit = limit.coerceIn(1, 10000)
        val validOrder = if (order in listOf("asc", "desc")) order else "desc"
        
        log.info("Listing files with purpose: $purpose, limit: $validLimit, order: $validOrder, after: $after")
        val files = fileService.listFiles(purpose, validLimit, validOrder, after)
        return ResponseEntity.ok(files)
    }

    @GetMapping("/files/{file_id}")
    @Operation(
        summary = "Retrieve file",
        description = "Returns information about a specific file.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The File object matching the specified ID",
                content = [Content(schema = Schema(implementation = File::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "File not found",
            ),
        ],
    )
    suspend fun getFile(
        @Parameter(description = "The ID of the file to use for this request", required = true)
        @PathVariable("file_id") fileId: String,
    ): ResponseEntity<File> {
        try {
            log.info("Getting file: $fileId")
            val file = fileService.getFile(fileId)
            return ResponseEntity.ok(file)
        } catch (e: FileNotFoundException) {
            log.error("File not found: $fileId")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    @DeleteMapping("/files/{file_id}")
    @Operation(
        summary = "Delete file",
        description = "Delete a file.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Deletion status",
                content = [Content(schema = Schema(implementation = FileDeleteResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "File not found",
            ),
        ],
    )
    suspend fun deleteFile(
        @Parameter(description = "The ID of the file to use for this request", required = true)
        @PathVariable("file_id") fileId: String,
    ): ResponseEntity<FileDeleteResponse> {
        try {
            log.info("Deleting file: $fileId")
            val deleteResponse = fileService.deleteFile(fileId)
            return ResponseEntity.ok(deleteResponse)
        } catch (e: FileNotFoundException) {
            log.error("File not found: $fileId")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    @GetMapping("/files/{file_id}/content")
    @Operation(
        summary = "Retrieve file content",
        description = "Returns the contents of the specified file.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The file content",
            ),
            ApiResponse(
                responseCode = "404",
                description = "File not found",
            ),
        ],
    )
    suspend fun getFileContent(
        @Parameter(description = "The ID of the file to use for this request", required = true)
        @PathVariable("file_id") fileId: String,
    ): ResponseEntity<Resource> {
        try {
            log.info("Getting file content: $fileId")
            val resource = fileService.getFileContent(fileId)
            
            // Create the response with appropriate headers for file download
            val file = fileService.getFile(fileId)
            val contentType = determineContentType(file.filename)
            
            return ResponseEntity
                .ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${file.filename}\"")
                .body(resource)
        } catch (e: FileNotFoundException) {
            log.error("File not found: $fileId")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    /**
     * Determines the content type based on the file extension.
     */
    private fun determineContentType(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "jsonl" -> "application/jsonl"
            "xml" -> "application/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }
} 
