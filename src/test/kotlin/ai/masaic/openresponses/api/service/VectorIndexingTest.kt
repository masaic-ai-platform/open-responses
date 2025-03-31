package ai.masaic.openresponses.api.service

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Service for handling vector indexing of files.
 */
class VectorIndexingService(
    private val vectorSearchProvider: VectorSearchProvider,
) {
    /**
     * Process a file for vector indexing.
     * 
     * @param fileId The ID of the file
     * @param purpose The purpose of the file
     */
    suspend fun processFile(
        fileId: String,
        purpose: String,
    ) {
        // Only index files with assistants or user_data purpose
        if (purpose != "assistants" && purpose != "user_data") {
            return
        }
        
        // Actual implementation would load the file and index it
    }
}

class VectorIndexingTest {
    private lateinit var fileService: FileService
    private lateinit var fileStorageService: FileStorageService
    private lateinit var vectorSearchProvider: VectorSearchProvider
    private lateinit var vectorIndexingService: VectorIndexingService
    private lateinit var mockFile: MultipartFile
    private lateinit var mockResource: Resource
    private var postProcessHook: (suspend (String, String) -> Unit)? = null

    @BeforeEach
    fun setup() {
        fileStorageService = mockk()
        vectorSearchProvider = mockk()
        
        coEvery { vectorSearchProvider.indexFile(any(), any(), any()) } returns true
        coEvery { vectorSearchProvider.deleteFile(any()) } returns true
        
        vectorIndexingService = VectorIndexingService(vectorSearchProvider)
        
        // Set up default mock behaviors
        every { fileStorageService.registerPostProcessHook(any()) } answers {
            postProcessHook = firstArg()
        }
        
        fileService = FileService(fileStorageService, vectorSearchProvider)

        mockFile =
            MockMultipartFile(
                "file",
                "test.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "test content".toByteArray(),
            )

        // Setup mock resources
        mockResource = mockk<ByteArrayResource>()
        val mockInputStream = mockk<InputStream>()
        every { mockResource.inputStream } returns mockInputStream
        every { mockResource.filename } returns "test.txt"
    }

    @Test
    fun `hook should index assistants files`() =
        runTest {
            // Given
            val fileId = "file-123"
            val purpose = "assistants"

            coEvery { fileStorageService.store(any(), any()) } returns fileId
            coEvery { fileStorageService.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to purpose,
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileStorageService.loadAsResource(fileId) } returns mockResource

            // When
            // 1. Upload a file
            val uploadedFile = fileService.uploadFile(mockFile, purpose)
        
            // Then
            // 1. File should be uploaded
            assertEquals(fileId, uploadedFile.id)
        
            // 2. Test the hook was triggered for assistants purpose
            postProcessHook?.invoke(fileId, purpose)
        
            // 3. Verify file was indexed
            coVerify { fileStorageService.loadAsResource(fileId) }
            coVerify { vectorSearchProvider.indexFile(fileId, any(), any()) }
        }

    @Test
    fun `hook should not index files with other purposes`() =
        runTest {
            // Given
            val fileId = "file-123"
            val purpose = "fine_tune" // Not assistants or user_data
        
            coEvery { fileStorageService.store(any(), any()) } returns fileId
            coEvery { fileStorageService.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to purpose,
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileStorageService.loadAsResource(fileId) } returns mockResource
        
            // When
            // 1. Upload a file
            val uploadedFile = fileService.uploadFile(mockFile, purpose)
        
            // Then
            // 1. File should be uploaded
            assertEquals(fileId, uploadedFile.id)
        
            // 2. Test the hook was triggered but should not index
            postProcessHook?.invoke(fileId, purpose)
        
            // 3. Verify file was NOT indexed for fine-tune purpose
            verify(exactly = 0) { vectorSearchProvider.indexFile(fileId, any(), any()) }
        }

    @Test
    fun `hook should handle user_data purpose files`() =
        runTest {
            // Given
            val fileId = "file-123"
            val purpose = "user_data"
        
            coEvery { fileStorageService.store(any(), any()) } returns fileId
            coEvery { fileStorageService.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to purpose,
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileStorageService.loadAsResource(fileId) } returns mockResource
        
            // When
            // 1. Upload a file
            val uploadedFile = fileService.uploadFile(mockFile, purpose)
        
            // Then
            // 1. File should be uploaded
            assertEquals(fileId, uploadedFile.id)
        
            // 2. Test the hook was triggered for user_data purpose
            postProcessHook?.invoke(fileId, purpose)
        
            // 3. Verify file was indexed
            coVerify { fileStorageService.loadAsResource(fileId) }
            coVerify { vectorSearchProvider.indexFile(fileId, any(), any()) }
        }

    @Test
    fun `deleteFile should delete from vector store when file is deleted`() =
        runTest {
            // Given
            val fileId = "file-123"
        
            coEvery { fileStorageService.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileStorageService.delete(fileId) } returns true
            coEvery { fileStorageService.exists(fileId) } returns true
        
            // When
            val result = fileService.deleteFile(fileId)
        
            // Then
            assertTrue(result.deleted)
            coVerify { vectorSearchProvider.deleteFile(fileId) }
        }

    @Test
    fun `mock classes work as expected`() =
        runTest {
            // This test validates that our mocking is set up correctly
        
            // Given
            val fileId = "test-file-id"
            val content = "This is test content".toByteArray()
            val mockFile = MockMultipartFile("file", "test.txt", "text/plain", content)
        
            coEvery { fileStorageService.store(any(), any()) } returns fileId
        
            coEvery { fileStorageService.loadAsResource(fileId) } returns ByteArrayResource(content)
        
            coEvery { fileStorageService.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants", 
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
        
            // When we upload a file
            val uploadedFile = fileService.uploadFile(mockFile, "assistants")
        
            // Then it should be stored correctly
            assertEquals(fileId, uploadedFile.id)
            assertEquals("test.txt", uploadedFile.filename)
            assertEquals("assistants", uploadedFile.purpose)
        }
}
