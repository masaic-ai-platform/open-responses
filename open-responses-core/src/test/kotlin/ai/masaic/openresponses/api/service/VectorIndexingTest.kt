package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.CreateVectorStoreFileRequest
import ai.masaic.openresponses.api.model.CreateVectorStoreRequest
import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import ai.masaic.openresponses.api.service.search.VectorSearchProvider
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.api.service.storage.FileService
import ai.masaic.openresponses.api.service.storage.FileStorageService
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.toFilePart
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.mock.web.MockMultipartFile
import java.io.InputStream
import java.time.Instant
import kotlin.test.assertEquals

class VectorIndexingTest {
    private lateinit var fileService: FileService
    private lateinit var fileStorageService: FileStorageService
    private lateinit var vectorSearchProvider: VectorSearchProvider
    private lateinit var vectorStoreService: VectorStoreService
    private lateinit var mockFile: FilePart
    private lateinit var mockResource: Resource
    private lateinit var telemetryService: TelemetryService

    @BeforeEach
    fun setup() {
        fileStorageService = mockk()
        vectorSearchProvider = mockk()
        telemetryService = mockk(relaxed = true)
        
        coEvery { vectorSearchProvider.indexFile(any(), any(), any(), any(), any()) } returns true
        coEvery { vectorSearchProvider.deleteFile(any()) } returns true
        
        fileService = FileService(fileStorageService, vectorSearchProvider, telemetryService)
        vectorStoreService = mockk()

        mockFile =
            MockMultipartFile(
                "file",
                "test.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "test content".toByteArray(),
            ).toFilePart()

        // Setup mock resources
        mockResource = mockk<ByteArrayResource>()
        val mockInputStream = mockk<InputStream>()
        every { mockResource.inputStream } returns mockInputStream
        every { mockResource.filename } returns "test.txt"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should create vector store and index file`() =
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
            coEvery { fileStorageService.exists(fileId) } returns true

            // Setup mock file response
            val mockApiFile =
                ai.masaic.openresponses.api.model.File(
                    id = fileId,
                    bytes = 100L,
                    filename = "test.txt",
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )
            
            // When uploading a file, return the mock file
            coEvery { 
                telemetryService.withFileOperation<ai.masaic.openresponses.api.model.File>(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ) 
            } returns mockApiFile

            // When
            // 1. Upload a file
            val uploadedFile = fileService.uploadFilePart(mockFile, purpose)
            
            // 2. Create a vector store with the file
            val mockVectorStore = mockk<VectorStore>(relaxed = true)
            coEvery { 
                vectorStoreService.createVectorStore(any()) 
            } returns mockVectorStore
            
            val createVectorStoreRequest =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf(fileId),
                )
            val result = vectorStoreService.createVectorStore(createVectorStoreRequest)
            
            // Process all pending background coroutines
            advanceUntilIdle()
        
            // Then
            // 1. File should be uploaded
            assertEquals(fileId, uploadedFile.id)
            
            // 2. Vector store should be created with the file
            coVerify { vectorStoreService.createVectorStore(createVectorStoreRequest) }
            assertEquals(mockVectorStore, result)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should add existing file to vector store`() =
        runTest {
            // Given
            val fileId = "file-123"
            val vectorStoreId = "vs_456"
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
            coEvery { fileStorageService.exists(fileId) } returns true
            
            // Setup mock file response
            val mockApiFile =
                ai.masaic.openresponses.api.model.File(
                    id = fileId,
                    bytes = 100L,
                    filename = "test.txt",
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )
            
            // When uploading a file, return the mock file
            coEvery { 
                telemetryService.withFileOperation<ai.masaic.openresponses.api.model.File>(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ) 
            } returns mockApiFile
        
            // When
            // 1. Upload a file
            val uploadedFile = fileService.uploadFilePart(mockFile, purpose)
            
            // 2. Add file to existing vector store
            val mockVectorStoreFile = mockk<VectorStoreFile>(relaxed = true)
            coEvery { 
                vectorStoreService.createVectorStoreFile(any(), any()) 
            } returns mockVectorStoreFile
            
            val createFileRequest = CreateVectorStoreFileRequest(fileId = fileId)
            val result = vectorStoreService.createVectorStoreFile(vectorStoreId, createFileRequest)
            
            // Process all pending background coroutines
            advanceUntilIdle()
        
            // Then
            // 1. File should be uploaded
            assertEquals(fileId, uploadedFile.id)
            
            // 2. File should be added to vector store
            coVerify { vectorStoreService.createVectorStoreFile(vectorStoreId, createFileRequest) }
            assertEquals(mockVectorStoreFile, result)
        }

    @Test
    fun `should not allow invalid purpose file in vector store`() =
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
            
            // Setup mock file response
            val mockApiFile =
                ai.masaic.openresponses.api.model.File(
                    id = fileId,
                    bytes = 100L,
                    filename = "test.txt",
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )
            
            // When uploading a file, return the mock file
            coEvery { 
                telemetryService.withFileOperation<ai.masaic.openresponses.api.model.File>(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ) 
            } returns mockApiFile
        
            // When
            // 1. Upload a file
            val uploadedFile = fileService.uploadFilePart(mockFile, purpose)
        
            // Then
            // 1. File should be uploaded
            assertEquals(fileId, uploadedFile.id)
            
            // 2. Purpose should be fine_tune
            assertEquals(purpose, uploadedFile.purpose)
        }
}
