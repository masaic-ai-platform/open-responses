package ai.masaic.openresponses.api.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class LocalFileStorageServiceTest {
    private lateinit var fileStorageService: LocalFileStorageService
    
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        fileStorageService = LocalFileStorageService(tempDir.toString())
        fileStorageService.init()
    }

    @AfterEach
    fun cleanup() {
        // Clean up directories
        if (Files.exists(tempDir)) {
            Files
                .walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `init should create root directory and purpose subdirectories`() {
        // Verify root directory exists
        assertTrue(Files.exists(tempDir))
        
        // Verify purpose subdirectories exist
        for (purpose in listOf("assistants", "batch", "fine-tune", "vision", "user_data", "evals")) {
            assertTrue(Files.exists(tempDir.resolve(purpose)))
        }
    }

    @Test
    fun `store should save file and return file ID`() {
        // Given
        val content = "Test file content"
        val file =
            MockMultipartFile(
                "file", 
                "test.txt", 
                MediaType.TEXT_PLAIN_VALUE, 
                content.toByteArray(),
            )
        val purpose = "assistants"
        
        // When
        val fileId = fileStorageService.store(file, purpose)
        
        // Then
        assertNotNull(fileId)
        assertTrue(fileId.startsWith("file-"))
        
        // Verify file is saved in the correct location
        val filePath = tempDir.resolve(purpose).resolve(fileId)
        assertTrue(Files.exists(filePath))
        
        // Verify file content
        val savedContent = String(Files.readAllBytes(filePath))
        assertEquals(content, savedContent)
    }

    @Test
    fun `loadAll should return all files`() {
        // Given
        val file1 = MockMultipartFile("file1", "test1.txt", MediaType.TEXT_PLAIN_VALUE, "content1".toByteArray())
        val file2 = MockMultipartFile("file2", "test2.txt", MediaType.TEXT_PLAIN_VALUE, "content2".toByteArray())
        
        val id1 = fileStorageService.store(file1, "assistants")
        val id2 = fileStorageService.store(file2, "batch")
        
        // When
        val files = fileStorageService.loadAll().toList()
        
        // Then
        assertTrue(files.size >= 2)
        assertTrue(files.any { it.fileName.toString() == id1 })
        assertTrue(files.any { it.fileName.toString() == id2 })
    }

    @Test
    fun `loadByPurpose should return files for specific purpose`() {
        // Given
        val file1 = MockMultipartFile("file1", "test1.txt", MediaType.TEXT_PLAIN_VALUE, "content1".toByteArray())
        val file2 = MockMultipartFile("file2", "test2.txt", MediaType.TEXT_PLAIN_VALUE, "content2".toByteArray())
        
        val id1 = fileStorageService.store(file1, "assistants")
        val id2 = fileStorageService.store(file2, "batch")
        
        // When
        val assistantFiles = fileStorageService.loadByPurpose("assistants").toList()
        val batchFiles = fileStorageService.loadByPurpose("batch").toList()
        
        // Then
        assertEquals(1, assistantFiles.size)
        assertEquals(id1, assistantFiles[0].fileName.toString())
        
        assertEquals(1, batchFiles.size)
        assertEquals(id2, batchFiles[0].fileName.toString())
    }

    @Test
    fun `load should find file in any purpose directory`() {
        // Given
        val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "content".toByteArray())
        val id = fileStorageService.store(file, "assistants")
        
        // When
        val filePath = fileStorageService.load(id)
        
        // Then
        assertTrue(Files.exists(filePath))
        assertEquals(id, filePath.fileName.toString())
    }

    @Test
    fun `load should throw FileNotFoundException when file does not exist`() {
        // Given
        val nonExistentId = "file-" + UUID.randomUUID().toString()
        
        // When/Then
        assertThrows<FileNotFoundException> {
            fileStorageService.load(nonExistentId)
        }
    }

    @Test
    fun `loadAsResource should return a readable Resource for existing file`() {
        // Given
        val content = "Test resource content"
        val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, content.toByteArray())
        val id = fileStorageService.store(file, "assistants")
        
        // When
        val resource = fileStorageService.loadAsResource(id)
        
        // Then
        assertTrue(resource.exists())
        assertTrue(resource.isReadable)
        val loadedContent = resource.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(content, loadedContent)
    }

    @Test
    fun `loadAsResource should throw FileNotFoundException for non-existent file`() {
        // Given
        val nonExistentId = "file-" + UUID.randomUUID().toString()
        
        // When/Then
        assertThrows<FileNotFoundException> {
            fileStorageService.loadAsResource(nonExistentId)
        }
    }

    @Test
    fun `delete should remove file and return true if successful`() {
        // Given
        val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "content".toByteArray())
        val id = fileStorageService.store(file, "assistants")
        val filePath = fileStorageService.load(id)
        assertTrue(Files.exists(filePath))
        
        // When
        val result = fileStorageService.delete(id)
        
        // Then
        assertTrue(result)
        assertFalse(Files.exists(filePath))
    }

    @Test
    fun `delete should return false when file doesn't exist`() {
        // Given
        val nonExistentId = "file-" + UUID.randomUUID().toString()
        
        // When
        val result = fileStorageService.delete(nonExistentId)
        
        // Then
        assertFalse(result)
    }

    @Test
    fun `getFileMetadata should return correct metadata`() {
        // Given
        val fileName = "metadata-test.txt"
        val content = "Test content for metadata"
        val file = MockMultipartFile("file", fileName, MediaType.TEXT_PLAIN_VALUE, content.toByteArray())
        val purpose = "assistants"
        val id = fileStorageService.store(file, purpose)
        
        // When
        val metadata = fileStorageService.getFileMetadata(id)
        
        // Then
        assertEquals(id, metadata["id"])
        assertEquals(fileName, metadata["filename"])
        assertEquals(purpose, metadata["purpose"])
        assertEquals(content.toByteArray().size.toLong(), metadata["bytes"])
        assertTrue(metadata.containsKey("created_at"))
        assertTrue(metadata["created_at"] is Long)
    }

    @Test
    fun `exists should return true for existing file`() {
        // Given
        val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "content".toByteArray())
        val id = fileStorageService.store(file, "assistants")
        
        // When
        val exists = fileStorageService.exists(id)
        
        // Then
        assertTrue(exists)
    }

    @Test
    fun `exists should return false for non-existent file`() {
        // Given
        val nonExistentId = "file-" + UUID.randomUUID().toString()
        
        // When
        val exists = fileStorageService.exists(nonExistentId)
        
        // Then
        assertFalse(exists)
    }

    @Test
    fun `registerPostProcessHook should execute hook on file store`() {
        // Given
        var hookCalled = false
        var hookFileId: String? = null
        var hookPurpose: String? = null
        
        fileStorageService.registerPostProcessHook { fileId, purpose ->
            hookCalled = true
            hookFileId = fileId
            hookPurpose = purpose
        }
        
        val file = MockMultipartFile("file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "content".toByteArray())
        val purpose = "assistants"
        
        // When
        val fileId = fileStorageService.store(file, purpose)
        
        // Then
        assertTrue(hookCalled)
        assertEquals(fileId, hookFileId)
        assertEquals(purpose, hookPurpose)
    }
} 
