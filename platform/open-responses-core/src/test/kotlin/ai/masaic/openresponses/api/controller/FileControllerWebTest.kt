package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.model.FileDeleteResponse
import ai.masaic.openresponses.api.model.FileListResponse
import ai.masaic.openresponses.api.model.FilePurpose
import ai.masaic.openresponses.api.service.storage.FileNotFoundException
import ai.masaic.openresponses.api.service.storage.FileService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.reactive.function.BodyInserters
import java.time.Instant

class FileControllerWebTest {
    private lateinit var webTestClient: WebTestClient
    private lateinit var fileService: FileService

    @BeforeEach
    fun setUp() {
        fileService = mockk()
        val controller = FileController(fileService)
        webTestClient = WebTestClient.bindToController(controller).build()
    }

    @Test
    @Disabled // TODO: Fix this test
    fun `uploadFile should return uploaded file on success`() =
        runTest {
            // Given
            val fileName = "test-file.txt"
            val purpose = FilePurpose.assistants.name
            val fileId = "file-123456"
            val createdAt = Instant.now().epochSecond
            val fileContent = "test content"

            val uploadedFile =
                File(
                    id = fileId,
                    bytes = 12L,
                    filename = fileName,
                    purpose = purpose,
                    createdAt = createdAt,
                )

            coEvery {
                fileService.uploadFilePart(any(), purpose)
            } answers {
                val file = firstArg<MultipartFile>()
                require(file.originalFilename == fileName) { "Filename doesn't match" }
                require(String(file.bytes) == fileContent) { "Content doesn't match" }
                uploadedFile
            }

            // When/Then - use MultipartBodyBuilder to properly handle the multipart request
            val bodyBuilder = MultipartBodyBuilder()
            bodyBuilder.part(
                "file",
                object : ByteArrayResource(fileContent.toByteArray()) {
                    override fun getFilename(): String = fileName
                },
            )

            webTestClient
                .post()
                .uri { builder ->
                    builder
                        .path("/v1/files")
                        .queryParam("purpose", purpose)
                        .build()
                }.contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(fileId)
                .jsonPath("$.filename")
                .isEqualTo(fileName)
                .jsonPath("$.purpose")
                .isEqualTo(purpose)
                .jsonPath("$.bytes")
                .isEqualTo(12)
                .jsonPath("$.created_at")
                .isEqualTo(createdAt)
                .jsonPath("$.object")
                .isEqualTo("file")
        }

    @Test
    fun `uploadFile should return 400 when purpose is invalid`() =
        runTest {
            // Given
            val fileName = "test-file.txt"
            val fileContent = "test content"
            val invalidPurpose = "invalid_purpose"

            coEvery { fileService.uploadFilePart(any(), invalidPurpose) } throws
                IllegalArgumentException("Invalid purpose: $invalidPurpose")

            // When/Then - use MultipartBodyBuilder to properly handle the multipart request
            val bodyBuilder = MultipartBodyBuilder()
            bodyBuilder.part("file", ByteArrayResource(fileContent.toByteArray())).filename(fileName)

            // When/Then
            webTestClient
                .post()
                .uri { builder ->
                    builder
                        .path("/v1/files")
                        .queryParam("purpose", invalidPurpose)
                        .build()
                }.contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                .exchange()
                .expectStatus()
                .isBadRequest
        }

    @Test
    fun `listFiles should return list of files`() =
        runTest {
            // Given
            val file1 =
                File(
                    id = "file-1",
                    bytes = 10L,
                    filename = "file1.txt",
                    purpose = FilePurpose.assistants.name,
                    createdAt = Instant.now().epochSecond,
                )

            val file2 =
                File(
                    id = "file-2",
                    bytes = 20L,
                    filename = "file2.txt",
                    purpose = FilePurpose.fine_tune.name,
                    createdAt = Instant.now().epochSecond,
                )

            val fileListResponse = FileListResponse(listOf(file1, file2))

            coEvery { fileService.listFiles(any(), any(), any(), any()) } returns fileListResponse

            // When/Then
            webTestClient
                .get()
                .uri("/v1/files")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.data.length()")
                .isEqualTo(2)
                .jsonPath("$.data[0].id")
                .isEqualTo("file-1")
                .jsonPath("$.data[1].id")
                .isEqualTo("file-2")
                .jsonPath("$.object")
                .isEqualTo("list")
        }

    @Test
    fun `listFiles should apply parameters`() =
        runTest {
            // Given
            val purpose = FilePurpose.assistants.name
            val limit = 5
            val file =
                File(
                    id = "file-1",
                    bytes = 10L,
                    filename = "file1.txt",
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )

            val fileListResponse = FileListResponse(listOf(file))

            coEvery { fileService.listFiles(purpose, limit, "desc", null) } returns fileListResponse

            // When/Then
            webTestClient
                .get()
                .uri { builder ->
                    builder
                        .path("/v1/files")
                        .queryParam("purpose", purpose)
                        .queryParam("limit", limit)
                        .build()
                }.exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.data.length()")
                .isEqualTo(1)
                .jsonPath("$.data[0].purpose")
                .isEqualTo(purpose)

            coVerify { fileService.listFiles(purpose, limit, "desc", null) }
        }

    @Test
    fun `getFile should return file by ID`() =
        runTest {
            // Given
            val fileId = "file-123"
            val file =
                File(
                    id = fileId,
                    bytes = 15L,
                    filename = "example.txt",
                    purpose = FilePurpose.assistants.name,
                    createdAt = Instant.now().epochSecond,
                )

            coEvery { fileService.getFile(fileId) } returns file

            // When/Then
            webTestClient
                .get()
                .uri("/v1/files/{file_id}", fileId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(fileId)
                .jsonPath("$.filename")
                .isEqualTo("example.txt")
        }

    @Test
    fun `getFile should return 404 when file not found`() =
        runTest {
            // Given
            val fileId = "file-nonexistent"

            coEvery { fileService.getFile(fileId) } throws FileNotFoundException("File not found: $fileId")

            // When/Then
            webTestClient
                .get()
                .uri("/v1/files/{file_id}", fileId)
                .exchange()
                .expectStatus()
                .isNotFound
        }

    @Test
    fun `deleteFile should return deletion status`() =
        runTest {
            // Given
            val fileId = "file-to-delete"
            val deleteResponse = FileDeleteResponse(id = fileId, deleted = true)

            coEvery { fileService.deleteFile(fileId) } returns deleteResponse

            // When/Then
            webTestClient
                .delete()
                .uri("/v1/files/{file_id}", fileId)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(fileId)
                .jsonPath("$.deleted")
                .isEqualTo(true)
                .jsonPath("$.object")
                .isEqualTo("file")
        }

    @Test
    fun `deleteFile should return 404 when file not found`() =
        runTest {
            // Given
            val fileId = "file-nonexistent"

            coEvery { fileService.deleteFile(fileId) } throws FileNotFoundException("File not found: $fileId")

            // When/Then
            webTestClient
                .delete()
                .uri("/v1/files/{file_id}", fileId)
                .exchange()
                .expectStatus()
                .isNotFound
        }

    @Test
    fun `getFileContent should return file content`() =
        runTest {
            // Given
            val fileId = "file-123"
            val fileContent = "This is the file content"
            val contentResource = ByteArrayResource(fileContent.toByteArray())

            val file =
                File(
                    id = fileId,
                    bytes = fileContent.length.toLong(),
                    filename = "document.txt",
                    purpose = FilePurpose.assistants.name,
                    createdAt = Instant.now().epochSecond,
                )

            coEvery { fileService.getFileContent(fileId) } returns contentResource
            coEvery { fileService.getFile(fileId) } returns file

            // When/Then
            webTestClient
                .get()
                .uri("/v1/files/{file_id}/content", fileId)
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .contentType(MediaType.TEXT_PLAIN)
                .expectBody()
                .consumeWith { result ->
                    val body = result.responseBodyContent ?: ByteArray(0)
                    assert(body.contentEquals(fileContent.toByteArray()))
                }
        }

    @Test
    fun `getFileContent should return 404 when file not found`() =
        runTest {
            // Given
            val fileId = "file-nonexistent"

            coEvery { fileService.getFileContent(fileId) } throws FileNotFoundException("File not found: $fileId")

            // When/Then
            webTestClient
                .get()
                .uri("/v1/files/{file_id}/content", fileId)
                .exchange()
                .expectStatus()
                .isNotFound
        }
}
