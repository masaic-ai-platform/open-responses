package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.service.search.VectorSearchProvider
import ai.masaic.openresponses.api.service.storage.FileNotFoundException
import ai.masaic.openresponses.api.service.storage.FileService
import ai.masaic.openresponses.api.service.storage.FileStorageService
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.toFilePart
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(SpringExtension::class)
class FileServiceTest {
    private lateinit var fileService: FileService
    private lateinit var fileStorageService: FileStorageService
    private lateinit var vectorSearchProvider: VectorSearchProvider
    private lateinit var telemetryService: TelemetryService

    @BeforeEach
    fun setup() {
        fileStorageService = mockk()
        vectorSearchProvider = mockk()
        telemetryService = mockk(relaxed = true)

        // Set up default mock behaviors
        // Mock behavior: just capture invocation
        every { fileStorageService.registerPostProcessHook(any()) } answers {
            val hook = firstArg<suspend (String, String) -> Unit>()
            // simulate the callback being invoked - we can't actually run it since it's suspending
        }
        fileService = FileService(fileStorageService, vectorSearchProvider, telemetryService)
    }

    @Test
    fun `uploadFile should validate purpose and store file`() =
        runTest {
            // Given
            val file =
                MockMultipartFile(
                    "file", 
                    "test.txt", 
                    MediaType.TEXT_PLAIN_VALUE, 
                    "test content".toByteArray(),
                ).toFilePart()
            val purpose = "assistants"
            val fileId = "file-123456"
        
            // Mock file storage and telemetry
            coEvery { fileStorageService.store(file, purpose) } returns fileId
            
            // Properly mock the telemetry service to return our expected File
            val expectedFile =
                ai.masaic.openresponses.api.model.File(
                    id = fileId,
                    bytes = 0,
                    filename = "test.txt",
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )
            
            coEvery { 
                telemetryService.withFileOperation<ai.masaic.openresponses.api.model.File>(
                    operationName = "upload",
                    fileId = "temp",
                    fileName = "test.txt",
                    purpose = purpose,
                    block = any(),
                ) 
            } coAnswers { call ->
                // Extract the lambda block and call it
                val block = call.invocation.args[4] as (suspend () -> ai.masaic.openresponses.api.model.File)
                block() // This will call our mocked fileStorageService.store
                expectedFile
            }
        
            // When
            val result = fileService.uploadFilePart(file, purpose)
        
            // Then
            assertEquals(fileId, result.id)
            assertEquals("test.txt", result.filename)
            assertEquals(purpose, result.purpose)

            coVerify { fileStorageService.store(file, purpose) }
        }

    @Test
    fun `uploadFile should throw IllegalArgumentException for invalid purpose`() =
        runTest {
            // Given
            val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "content".toByteArray()).toFilePart()
            val invalidPurpose = "invalid_purpose"
        
            // When/Then
            assertThrows<IllegalArgumentException> {
                fileService.uploadFilePart(file, invalidPurpose)
            }
        }

    @Test
    fun `listFiles should return files from storage`() =
        runTest {
            // Given
            val path1 = Paths.get("/tmp/file-1")
            val path2 = Paths.get("/tmp/file-2")
        
            val metadata1 =
                mapOf(
                    "id" to "file-1",
                    "filename" to "file1.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
        
            val metadata2 =
                mapOf(
                    "id" to "file-2",
                    "filename" to "file2.txt",
                    "purpose" to "batch",
                    "bytes" to 200L,
                    "created_at" to Instant.now().epochSecond,
                )
        
            every { fileStorageService.loadAll() } returns flowOf(path1, path2)
            coEvery { fileStorageService.getFileMetadata("file-1") } returns metadata1
            coEvery { fileStorageService.getFileMetadata("file-2") } returns metadata2
        
            // When
            val result = fileService.listFiles()
        
            // Then
            assertEquals(2, result.data.size)
        }

    @Test
    fun `listFiles should filter by purpose when specified`() =
        runTest {
            // Given
            val path = Paths.get("/tmp/file-1")
            val purpose = "assistants"
        
            val metadata =
                mapOf(
                    "id" to "file-1",
                    "filename" to "file1.txt",
                    "purpose" to purpose,
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
        
            every { fileStorageService.loadByPurpose(purpose) } returns flowOf(path)
            coEvery { fileStorageService.getFileMetadata("file-1") } returns metadata
        
            // When
            val result = fileService.listFiles(purpose)
        
            // Then
            assertEquals(1, result.data.size)
            assertEquals("file-1", result.data[0].id)
            assertEquals(purpose, result.data[0].purpose)
        }

    @Test
    fun `getFile should return file with metadata`() =
        runTest {
            // Given
            val fileId = "file-123"
            val metadata =
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
        
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.getFileMetadata(fileId) } returns metadata
        
            // When
            val result = fileService.getFile(fileId)
        
            // Then
            assertEquals(fileId, result.id)
            assertEquals("test.txt", result.filename)
            assertEquals("assistants", result.purpose)
            assertEquals(100L, result.bytes)
        }

    @Test
    fun `getFile should throw FileNotFoundException when file doesn't exist`() =
        runTest {
            // Given
            val fileId = "file-nonexistent"
        
            coEvery { fileStorageService.exists(fileId) } returns false
        
            // When/Then
            assertThrows<FileNotFoundException> {
                fileService.getFile(fileId)
            }
        }

    @Test
    fun `getFileContent should return resource from storage`() =
        runTest {
            // Given
            val fileId = "file-123"
            val mockResource = mockk<Resource>()
        
            coEvery { fileStorageService.loadAsResource(fileId) } returns mockResource
        
            // When
            val result = fileService.getFileContent(fileId)
        
            // Then
            assertEquals(mockResource, result)
        }

    @Test
    fun `deleteFile should return deletion response`() =
        runTest {
            // Given
            val fileId = "file-123"
        
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.delete(fileId) } returns true
            coEvery { vectorSearchProvider.deleteFile(fileId) } returns true
        
            // When
            val result = fileService.deleteFile(fileId)
        
            // Then
            assertEquals(fileId, result.id)
            assertTrue(result.deleted)
        
            coVerify { 
                fileStorageService.exists(fileId)
                fileStorageService.delete(fileId)
                vectorSearchProvider.deleteFile(fileId)
            }
        }

    @Test
    fun `deleteFile should return false when file cannot be deleted`() =
        runTest {
            // Given
            val fileId = "file-123"
        
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.delete(fileId) } returns false
            coEvery { vectorSearchProvider.deleteFile(fileId) } returns true
        
            // When
            val result = fileService.deleteFile(fileId)
        
            // Then
            assertEquals(fileId, result.id)
            assertFalse(result.deleted)
        
            coVerify { 
                fileStorageService.delete(fileId)
                fileStorageService.exists(fileId)
            }
        }

    @Test
    fun `deleteFile should throw FileNotFoundException when file doesn't exist`() =
        runTest {
            // Given
            val fileId = "file-nonexistent"
        
            coEvery { fileStorageService.exists(fileId) } returns false
        
            // When/Then
            assertThrows<FileNotFoundException> {
                fileService.deleteFile(fileId)
            }
        }

    @Test
    fun `deleteFile should propagate error from vector search provider but complete file deletion`() =
        runTest {
            // Given
            val fileId = "file-123"
        
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.delete(fileId) } returns true
            every { vectorSearchProvider.deleteFile(fileId) } throws RuntimeException("Vector search error")
        
            // When
            val result = fileService.deleteFile(fileId)
        
            // Then
            assertEquals(fileId, result.id)
            assertTrue(result.deleted)
            
            // Verify that both storage and vector search deletion were attempted
            coVerify { fileStorageService.delete(fileId) }
            verify { vectorSearchProvider.deleteFile(fileId) }
        }

    @Test
    fun `deleteFile should still work when vector search provider is null`() =
        runTest {
            // Given
            val fileId = "file-123"
            // Create a file service without vector search provider
            val fileServiceWithoutVectorSearch = FileService(fileStorageService, null, telemetryService)
        
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.delete(fileId) } returns true
        
            // When
            val result = fileServiceWithoutVectorSearch.deleteFile(fileId)
        
            // Then
            assertEquals(fileId, result.id)
            assertTrue(result.deleted)
        }

    @Test
    fun `listFiles with pagination should handle after parameter correctly`() =
        runTest {
            // Given
            val path1 = Paths.get("/tmp/file-1")
            val path2 = Paths.get("/tmp/file-2")
            val path3 = Paths.get("/tmp/file-3")
            
            val now = Instant.now().epochSecond
            
            val metadata1 =
                mapOf(
                    "id" to "file-1",
                    "filename" to "file1.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to now - 200,
                )
            
            val metadata2 =
                mapOf(
                    "id" to "file-2",
                    "filename" to "file2.txt",
                    "purpose" to "assistants",
                    "bytes" to 200L,
                    "created_at" to now - 100,
                )
            
            val metadata3 =
                mapOf(
                    "id" to "file-3",
                    "filename" to "file3.txt",
                    "purpose" to "assistants",
                    "bytes" to 300L,
                    "created_at" to now,
                )
            
            every { fileStorageService.loadAll() } returns flowOf(path1, path2, path3)
            coEvery { fileStorageService.getFileMetadata("file-1") } returns metadata1
            coEvery { fileStorageService.getFileMetadata("file-2") } returns metadata2
            coEvery { fileStorageService.getFileMetadata("file-3") } returns metadata3
            
            // When using default order (desc), file-3 is the first item in the sorted list
            // When - Get files after file-3 with default ordering (desc)
            val result = fileService.listFiles(after = "file-3")
            
            // Then - Should return file-1 and file-2 as they come after file-3 in the sorted array
            assertEquals(2, result.data.size, "Should return files after file-3 in the sorted array")
            assertTrue(result.data.any { it.id == "file-1" }, "Should contain file-1")
            assertTrue(result.data.any { it.id == "file-2" }, "Should contain file-2")
            
            // When - Get files after file-2 with descending order
            val result2 = fileService.listFiles(after = "file-2", order = "desc")
            
            // Then - Will return file-1 (and not file-3, since file-3 comes before file-2 in desc order)
            assertEquals(1, result2.data.size, "Should return files after file-2 in the sorted array")
            assertEquals("file-1", result2.data[0].id, "Should return file-1")
            
            // When - Get files after file-1 with ascending order
            val result3 = fileService.listFiles(after = "file-1", order = "asc")
            
            // Then
            assertEquals(2, result3.data.size, "Should return two files after file-1 in ascending order")
            assertEquals("file-2", result3.data[0].id, "First file should be file-2")
            assertEquals("file-3", result3.data[1].id, "Second file should be file-3")
        }

    @Test
    fun `listFiles should handle limit parameter correctly`() =
        runTest {
            // Given
            val paths = (1..5).map { Paths.get("/tmp/file-$it") }
            val metadatas =
                (1..5).map { i ->
                    mapOf(
                        "id" to "file-$i",
                        "filename" to "file$i.txt",
                        "purpose" to "assistants",
                        "bytes" to (i * 100L),
                        "created_at" to (Instant.now().epochSecond - (5 - i) * 100),
                    )
                }
            
            every { fileStorageService.loadAll() } returns flowOf(*paths.toTypedArray())
            
            for (i in 1..5) {
                coEvery { fileStorageService.getFileMetadata("file-$i") } returns metadatas[i - 1]
            }
            
            // When - Limit to 2 files
            val result = fileService.listFiles(limit = 2)
            
            // Then
            assertEquals(2, result.data.size, "Should return only 2 files")
            // Files should be ordered by creation time descending by default
            assertEquals("file-5", result.data[0].id, "First file should be newest")
            assertEquals("file-4", result.data[1].id, "Second file should be second newest")
        }

    @Test
    fun `uploadFilePart should handle large files`() =
        runTest {
            // Given - Create a mock large file (we'll just mock the size)
            val file =
                MockMultipartFile(
                    "file",
                    "large-file.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    ByteArray(1024), // Actual content doesn't matter as we'll mock the storage
                ).toFilePart()
            
            val purpose = "assistants"
            val fileId = "large-file-id"
            
            coEvery { fileStorageService.store(file, purpose) } returns fileId
            
            val expectedFile =
                ai.masaic.openresponses.api.model.File(
                    id = fileId,
                    bytes = 10 * 1024 * 1024, // Pretend it's 10MB
                    filename = "large-file.pdf",
                    purpose = purpose,
                    createdAt = Instant.now().epochSecond,
                )
            
            coEvery {
                telemetryService.withFileOperation<ai.masaic.openresponses.api.model.File>(
                    operationName = "upload",
                    fileId = "temp",
                    fileName = "large-file.pdf",
                    purpose = purpose,
                    block = any(),
                )
            } coAnswers {
                val block = call.invocation.args[4] as (suspend () -> ai.masaic.openresponses.api.model.File)
                block()
                expectedFile
            }
            
            // When
            val result = fileService.uploadFilePart(file, purpose)
            
            // Then
            assertEquals(fileId, result.id)
            assertEquals(10 * 1024 * 1024, result.bytes, "File size should match expected large file size")
        }

    @Test
    fun `getFile should handle files with unusual filenames`() =
        runTest {
            // Given
            val fileId = "file-special"
            val unusualFilename = "file@with#special_chars+and spaces.txt"
            
            val metadata =
                mapOf(
                    "id" to fileId,
                    "filename" to unusualFilename,
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.getFileMetadata(fileId) } returns metadata
            
            // When
            val result = fileService.getFile(fileId)
            
            // Then
            assertEquals(fileId, result.id)
            assertEquals(unusualFilename, result.filename, "Filename with special characters should be preserved")
        }

    @Test
    fun `deleteFile should handle case where file exists in storage but not in vector store`() =
        runTest {
            // Given
            val fileId = "file-123"
            
            coEvery { fileStorageService.exists(fileId) } returns true
            coEvery { fileStorageService.delete(fileId) } returns true
            every { vectorSearchProvider.deleteFile(fileId) } returns false // File not found in vector store
            
            // When
            val result = fileService.deleteFile(fileId)
            
            // Then
            assertEquals(fileId, result.id)
            assertTrue(result.deleted, "File should be considered deleted if storage deletion succeeded")
        }

    @Test
    fun `listFiles with invalid purpose should return empty list`() =
        runTest {
            // Given
            val invalidPurpose = "invalid_purpose"
            
            // Mock empty flow for invalid purpose
            every { fileStorageService.loadByPurpose(invalidPurpose) } returns flowOf()
            
            // When
            val result = fileService.listFiles(purpose = invalidPurpose)
            
            // Then
            assertEquals(0, result.data.size, "Should return empty list for invalid purpose")
        }
}
