package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.File
import ai.masaic.openresponses.api.service.storage.FileService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Instant

/**
 * The non-multipart endpoints in FileControllerTest.kt are tested and functioning correctly.
 */
@Disabled
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class FileControllerUploadTest {
    @MockkBean
    private lateinit var fileService: FileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    @Disabled // TODO: Fix this test
    fun `uploadFile should return uploaded file on success`() =
        runTest {
            // Given
            val fileName = "test.txt"
            val purpose = "assistants"
            val fileId = "file-123456"
            val createdAt = Instant.now().epochSecond
            val fileContent = "Test content".toByteArray()
        
            val file =
                File(
                    id = fileId,
                    filename = fileName,
                    purpose = purpose,
                    bytes = 100,
                    createdAt = createdAt,
                )
        
            coEvery { 
                fileService.uploadFilePart(any(), eq(purpose))
            } returns file
        
            val bodyBuilder = MultipartBodyBuilder()
            bodyBuilder
                .part("file", fileContent)
                .filename(fileName)
                .contentType(MediaType.TEXT_PLAIN)
        
            webTestClient
                .post()
                .uri { uriBuilder ->
                    uriBuilder
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
                .isEqualTo(100)
                .jsonPath("$.created_at")
                .isEqualTo(createdAt)
        
            coVerify { fileService.uploadFilePart(any(), eq(purpose)) }
        }

    @Test
    @Disabled // TODO: Fix this test
    fun `uploadFile should return 400 when purpose is invalid`() =
        runTest {
            // Given
            val fileName = "test.txt"
            val purpose = "invalid"
            val fileContent = "Test content".toByteArray()
        
            coEvery { 
                fileService.uploadFilePart(any(), eq(purpose))
            } throws IllegalArgumentException("Invalid purpose: $purpose")
        
            val bodyBuilder = MultipartBodyBuilder()
            bodyBuilder
                .part("file", fileContent)
                .filename(fileName)
                .contentType(MediaType.TEXT_PLAIN)
        
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
                .isBadRequest
        
            coVerify { fileService.uploadFilePart(any(), eq(purpose)) }
        }
} 
