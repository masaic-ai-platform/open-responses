package ai.masaic.openresponses.api.service

import io.mockk.*
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
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(SpringExtension::class)
class FileServiceTest {
    private lateinit var fileService: FileService
    private lateinit var fileStorageService: FileStorageService
    private lateinit var vectorSearchProvider: VectorSearchProvider

    @BeforeEach
    fun setup() {
        fileStorageService = mockk()
        vectorSearchProvider = mockk()

        // Set up default mock behaviors
        // Mock behavior: just capture invocation
        every { fileStorageService.registerPostProcessHook(any()) } answers {
            val hook = firstArg<(String, String) -> Unit>()
            // simulate the callback being invoked
            hook("param1", "param2")
        }
        fileService = FileService(fileStorageService, vectorSearchProvider)
    }

    @Test
    fun `uploadFile should validate purpose and store file`() {
        // Given
        val file =
            MockMultipartFile(
                "file", 
                "test.txt", 
                MediaType.TEXT_PLAIN_VALUE, 
                "test content".toByteArray(),
            )
        val purpose = "assistants"
        val fileId = "file-123456"
        
        every { fileStorageService.store(file, purpose) } returns fileId
        
        // When
        val result = fileService.uploadFile(file, purpose)
        
        // Then
        assertEquals(fileId, result.id)
        assertEquals("test.txt", result.filename)
        assertEquals(purpose, result.purpose)
        assertEquals(file.size, result.bytes)
        
        verify { fileStorageService.store(file, purpose) }
    }

    @Test
    fun `uploadFile should throw IllegalArgumentException for invalid purpose`() {
        // Given
        val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "content".toByteArray())
        val invalidPurpose = "invalid_purpose"
        
        // When/Then
        assertThrows<IllegalArgumentException> {
            fileService.uploadFile(file, invalidPurpose)
        }
    }

    @Test
    fun `listFiles should return files from storage`() {
        // Given
        val path1 = Paths.get("/tmp/file-1")
        val path2 = Paths.get("/tmp/file-2")
        val pathStream = Stream.of(path1, path2)
        
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
        
        every { fileStorageService.loadAll() } returns pathStream
        every { fileStorageService.getFileMetadata("file-1") } returns metadata1
        every { fileStorageService.getFileMetadata("file-2") } returns metadata2
        
        // When
        val result = fileService.listFiles()
        
        // Then
        assertEquals(2, result.data.size)
    }

    @Test
    fun `listFiles should filter by purpose when specified`() {
        // Given
        val path = Paths.get("/tmp/file-1")
        val pathStream = Stream.of(path)
        val purpose = "assistants"
        
        val metadata =
            mapOf(
                "id" to "file-1",
                "filename" to "file1.txt",
                "purpose" to purpose,
                "bytes" to 100L,
                "created_at" to Instant.now().epochSecond,
            )
        
        every { fileStorageService.loadByPurpose(purpose) } returns pathStream
        every { fileStorageService.getFileMetadata("file-1") } returns metadata
        
        // When
        val result = fileService.listFiles(purpose)
        
        // Then
        assertEquals(1, result.data.size)
        assertEquals("file-1", result.data[0].id)
        assertEquals(purpose, result.data[0].purpose)
    }

    @Test
    fun `getFile should return file with metadata`() {
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
        
        every { fileStorageService.exists(fileId) } returns true
        every { fileStorageService.getFileMetadata(fileId) } returns metadata
        
        // When
        val result = fileService.getFile(fileId)
        
        // Then
        assertEquals(fileId, result.id)
        assertEquals("test.txt", result.filename)
        assertEquals("assistants", result.purpose)
        assertEquals(100L, result.bytes)
    }

    @Test
    fun `getFile should throw FileNotFoundException when file doesn't exist`() {
        // Given
        val fileId = "file-nonexistent"
        
        every { fileStorageService.exists(fileId) } returns false
        
        // When/Then
        assertThrows<FileNotFoundException> {
            fileService.getFile(fileId)
        }
    }

    @Test
    fun `getFileContent should return resource from storage`() {
        // Given
        val fileId = "file-123"
        val mockResource = mockk<Resource>()
        
        every { fileStorageService.loadAsResource(fileId) } returns mockResource
        
        // When
        val result = fileService.getFileContent(fileId)
        
        // Then
        assertEquals(mockResource, result)
    }

    @Test
    fun `deleteFile should return deletion response`() {
        // Given
        val fileId = "file-123"
        
        every { fileStorageService.exists(fileId) } returns true
        every { fileStorageService.delete(fileId) } returns true
        
        // When
        val result = fileService.deleteFile(fileId)
        
        // Then
        assertEquals(fileId, result.id)
        assertTrue(result.deleted)
    }

    @Test
    fun `deleteFile should return false when deletion fails`() {
        // Given
        val fileId = "file-123"
        
        every { fileStorageService.exists(fileId) } returns true
        every { fileStorageService.delete(fileId) } returns false
        
        // When
        val result = fileService.deleteFile(fileId)
        
        // Then
        assertEquals(fileId, result.id)
        assertFalse(result.deleted)
    }

    @Test
    fun `deleteFile should throw FileNotFoundException when file doesn't exist`() {
        // Given
        val fileId = "file-nonexistent"
        
        every { fileStorageService.exists(fileId) } returns false
        
        // When/Then
        assertThrows<FileNotFoundException> {
            fileService.deleteFile(fileId)
        }
    }
}
