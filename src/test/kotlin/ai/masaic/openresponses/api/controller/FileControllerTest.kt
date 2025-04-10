package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.model.FileDeleteResponse
import ai.masaic.openresponses.api.model.FileListResponse
import ai.masaic.openresponses.api.service.storage.FileNotFoundException
import ai.masaic.openresponses.api.service.storage.FileService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

@WebFluxTest(FileController::class)
class FileControllerTest {
    @MockkBean
    private lateinit var fileService: FileService
    
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `getFile should return file by ID`() =
        runTest {
            // Given
            val fileId = "file-123"
            val file =
                File(
                    id = fileId,
                    filename = "test.txt",
                    purpose = "assistants",
                    bytes = 100,
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
                .isEqualTo("test.txt")
        
            coVerify { fileService.getFile(fileId) }
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
        
            coVerify { fileService.getFile(fileId) }
        }

    @Test
    fun `listFiles should return list of files`() =
        runTest {
            // Given
            val file1 =
                File(
                    id = "file-1",
                    filename = "file1.txt",
                    purpose = "assistants",
                    bytes = 100,
                    createdAt = Instant.now().epochSecond,
                )
        
            val file2 =
                File(
                    id = "file-2",
                    filename = "file2.txt",
                    purpose = "batch",
                    bytes = 200,
                    createdAt = Instant.now().epochSecond,
                )
        
            val fileList = FileListResponse(data = listOf(file1, file2))
        
            coEvery { 
                fileService.listFiles(null, 10000, "desc", null) 
            } returns fileList
        
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
        
            coVerify { fileService.listFiles(null, 10000, "desc", null) }
        }

    @Test
    fun `listFiles should apply parameters`() =
        runTest {
            // Given
            val purpose = "assistants"
            val limit = 10
            val order = "asc"
            val after = "some-id"
        
            val file1 =
                File(
                    id = "file-1",
                    filename = "file1.txt",
                    purpose = purpose,
                    bytes = 100,
                    createdAt = Instant.now().epochSecond,
                )
        
            val fileList = FileListResponse(data = listOf(file1))
        
            coEvery { 
                fileService.listFiles(purpose, limit, order, after) 
            } returns fileList
        
            // When/Then
            webTestClient
                .get()
                .uri { builder -> 
                    builder
                        .path("/v1/files")
                        .queryParam("purpose", purpose)
                        .queryParam("limit", limit)
                        .queryParam("order", order)
                        .queryParam("after", after)
                        .build() 
                }.exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.data.length()")
                .isEqualTo(1)
                .jsonPath("$.data[0].id")
                .isEqualTo("file-1")
        
            coVerify { fileService.listFiles(purpose, limit, order, after) }
        }

    @Test
    fun `deleteFile should return deletion status`() =
        runTest {
            // Given
            val fileId = "file-123"
            val deleteResponse =
                FileDeleteResponse(
                    id = fileId,
                    deleted = true,
                )
        
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
        
            coVerify { fileService.deleteFile(fileId) }
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
        
            coVerify { fileService.deleteFile(fileId) }
        }

    @Test
    fun `getFileContent should return file content`() =
        runTest {
            // Given
            val fileId = "file-123"
            val content = "File content for testing"
            val contentBytes = content.toByteArray()
        
            val file =
                File(
                    id = fileId,
                    filename = "document.txt",
                    purpose = "assistants",
                    bytes = contentBytes.size.toLong(),
                    createdAt = Instant.now().epochSecond,
                )
        
            val resource: Resource = ByteArrayResource(contentBytes)
        
            coEvery { fileService.getFileContent(fileId) } returns resource
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
                .expectHeader()
                .valueEquals("Content-Disposition", "attachment; filename=\"document.txt\"")
                .expectBody()
                .consumeWith { result ->
                    val body = result.responseBodyContent ?: ByteArray(0)
                    assert(body.contentEquals(contentBytes))
                }
        
            coVerify { 
                fileService.getFileContent(fileId)
                fileService.getFile(fileId)
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
        
            coVerify { fileService.getFileContent(fileId) }
        }
    
    // File upload tests are moved to a separate test file that inherits from a base class with the complete Spring context
    // since WebFluxTest has limitations with multipart handling
} 
