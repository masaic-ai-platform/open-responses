package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.model.FileDeleteResponse
import ai.masaic.openresponses.api.model.FileListResponse
import ai.masaic.openresponses.api.service.storage.FileNotFoundException
import ai.masaic.openresponses.api.service.storage.FileService
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.Part
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.file.Path

/**
 * Controller for the Files API.
 *
 * This controller handles endpoints for uploading, listing, retrieving, and deleting files.
 */
@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
class FileController(
    private val fileService: FileService,
) {
    private val log = LoggerFactory.getLogger(FileController::class.java)

    @PostMapping(
        "/files",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.MULTIPART_MIXED_VALUE],
    )
    suspend fun uploadFile(
        @RequestPart("file") part: Part,
        @RequestPart("purpose") purpose: String,
    ): ResponseEntity<File> {
        try {
            // 1) Normalize ANY Part into a FilePart
            val filePart: FilePart = getFilePart(part)

            log.info("Uploading file: '${filePart.filename()}' for purpose: $purpose")
            // 2) Single method call
            val uploaded = fileService.uploadFilePart(filePart, purpose)
            return ResponseEntity.ok(uploaded)
        } catch (e: IllegalArgumentException) {
            log.error("Invalid request: {}", e.message)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("Error uploading file", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error uploading file: ${e.message}",
            )
        }
    }

    @GetMapping("/files")
    suspend fun listFiles(
        @RequestParam(required = false) after: String?,
        @RequestParam(defaultValue = "10000") limit: Int,
        @RequestParam(defaultValue = "desc") order: String,
        @RequestParam(required = false) purpose: String?,
    ): ResponseEntity<FileListResponse> {
        val validLimit = limit.coerceIn(1, 10000)
        val validOrder = if (order in listOf("asc", "desc")) order else "desc"
        
        log.info("Listing files with purpose: $purpose, limit: $validLimit, order: $validOrder, after: $after")
        val files = fileService.listFiles(purpose, validLimit, validOrder, after)
        return ResponseEntity.ok(files)
    }

    @GetMapping("/files/{file_id}")
    suspend fun getFile(
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
    suspend fun deleteFile(
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
    suspend fun getFileContent(
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

    /**
     * Given a [Part], returns a [FilePart] instance.
     * If the [Part] is already a [FilePart], returns it as is.
     * Otherwise, creates a new [FilePart] instance that wraps the given [Part].
     * The new [FilePart] will have the same headers as the original [Part],
     * but will ensure that there is a filename in the Content-Disposition header.
     * If the original [Part] has no filename, the filename will be set to the part name with a ".bin" extension.
     */
    private fun getFilePart(part: Part): FilePart =
        when (part) {
            is FilePart -> part // already a FilePart
            else ->
                object : FilePart { // wrap DEFAULT/FORM‐FIELD/whatever
                    private val headers: HttpHeaders =
                        HttpHeaders().apply {
                            // carry over original headers if you like:
                            putAll(part.headers())
                            // ensure there's a filename ⇒ some uploader code may rely on it
                            contentDisposition =
                                part
                                    .headers()
                                    .contentDisposition.filename
                                    .let { cd ->
                                        ContentDisposition
                                            .builder("form-data")
                                            .name(part.name())
                                            .filename(cd ?: (part.name() + ".bin"))
                                            .build()
                                    }
                        }

                    override fun name(): String = part.name()

                    override fun filename(): String = part.headers().contentDisposition.filename ?: (part.name() + ".bin")

                    override fun headers(): HttpHeaders = headers

                    override fun content(): Flux<DataBuffer> = part.content()

                    override fun transferTo(dest: Path): Mono<Void> = DataBufferUtils.write(content(), dest).then()
                }
        }
} 
