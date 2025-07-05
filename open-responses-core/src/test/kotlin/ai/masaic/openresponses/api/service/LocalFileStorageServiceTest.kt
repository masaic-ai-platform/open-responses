package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.service.storage.LocalFileStorageService
import ai.masaic.openresponses.api.utils.toFilePart
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.*

class LocalFileStorageServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var fileStorageService: LocalFileStorageService

    @BeforeEach
    fun setUp() =
        runTest {
            fileStorageService = LocalFileStorageService(tempDir.toString(), ObjectMapper())
        }

    @AfterEach
    fun tearDown() {
        // Clean up any files created by the test
        Files
            .walk(tempDir)
            .filter { it != tempDir }
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `init should create necessary directories`() =
        runTest {
            // Test that the service creates all the required subdirectories
            val purposes = listOf("assistants", "batch", "fine_tune", "vision", "user_data", "evals")
            purposes.forEach { purpose ->
                val purposeDir = tempDir.resolve(purpose)
                assertTrue(Files.exists(purposeDir))
                assertTrue(Files.isDirectory(purposeDir))
            }
        }

    @Test
    fun `store should save file and return ID`() =
        runTest {
            // Given
            val fileName = "test-file.txt"
            val fileContent = "Test content"
            val purpose = "assistants"
            val file =
                MockMultipartFile(
                    "file",
                    fileName,
                    MediaType.TEXT_PLAIN_VALUE,
                    fileContent.toByteArray(),
                ).toFilePart()

            // When
            val fileId = fileStorageService.store(file, purpose)

            // Then
            assertTrue(fileId.startsWith("open-responses"))
            val filePath = tempDir.resolve(purpose).resolve(fileId)
            assertTrue(Files.exists(filePath))
            assertEquals(fileContent, Files.readString(filePath))
        }

    @Test
    fun `loadAll should return all stored files`() =
        runTest {
            // Given
            val file1 =
                MockMultipartFile(
                    "file",
                    "file1.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Content 1".toByteArray(),
                ).toFilePart()
            val file2 =
                MockMultipartFile(
                    "file",
                    "file2.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Content 2".toByteArray(),
                ).toFilePart()

            // Store files in two different purposes
            val fileId1 = fileStorageService.store(file1, "assistants")
            val fileId2 = fileStorageService.store(file2, "batch")

            // When
            val allFiles = fileStorageService.loadAll().toList()

            // Then
            assertTrue(allFiles.size >= 2)
            assertTrue(allFiles.any { it.fileName.toString() == fileId1 })
            assertTrue(allFiles.any { it.fileName.toString() == fileId2 })
        }

    @Test
    fun `loadByPurpose should return files with matching purpose`() =
        runTest {
            // Given
            val file1 =
                MockMultipartFile(
                    "file1",
                    "file1.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Content 1".toByteArray(),
                ).toFilePart()
            val file2 =
                MockMultipartFile(
                    "file2",
                    "file2.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Content 2".toByteArray(),
                ).toFilePart()

            // Store files with different purposes
            val fileId1 = fileStorageService.store(file1, "assistants")
            val fileId2 = fileStorageService.store(file2, "batch")

            // When
            val assistantsFiles = fileStorageService.loadByPurpose("assistants").toList()
            val batchFiles = fileStorageService.loadByPurpose("batch").toList()

            // Then
            assertTrue(assistantsFiles.size >= 1)
            assertTrue(assistantsFiles.any { it.fileName.toString() == fileId1 })
        
            assertTrue(batchFiles.size >= 1)
            assertTrue(batchFiles.any { it.fileName.toString() == fileId2 })
        }

    @Test
    fun `load should find file by ID`() =
        runTest {
            // Given
            val file =
                MockMultipartFile(
                    "file",
                    "test.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Test content".toByteArray(),
                ).toFilePart()
            val fileId = fileStorageService.store(file, "assistants")

            // When
            val filePath = fileStorageService.load(fileId)

            // Then
            assertTrue(Files.exists(filePath))
            assertEquals(fileId, filePath.fileName.toString())
        }

    @Test
    fun `loadAsResource should return readable resource`() =
        runTest {
            // Given
            val content = "Test content for resource"
            val file =
                MockMultipartFile(
                    "file",
                    "test.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    content.toByteArray(),
                ).toFilePart()
            val fileId = fileStorageService.store(file, "assistants")

            // When
            val resource = fileStorageService.loadAsResource(fileId)

            // Then
            assertTrue(resource.exists())
            assertTrue(resource.isReadable)
            assertEquals(content, resource.inputStream.bufferedReader().use { it.readText() })
        }

    @Test
    fun `delete should remove file`() =
        runTest {
            // Given
            val file =
                MockMultipartFile(
                    "file",
                    "test.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Test content".toByteArray(),
                ).toFilePart()
            val fileId = fileStorageService.store(file, "assistants")
            val filePath = fileStorageService.load(fileId)

            // Pre-check
            assertTrue(Files.exists(filePath))

            // When
            val deleted = fileStorageService.delete(fileId)

            // Then
            assertTrue(deleted)
            assertFalse(Files.exists(filePath))
        }

    @Test
    fun `delete should return false when file doesn't exist`() =
        runTest {
            // When
            val deleted = fileStorageService.delete("non-existent-file")

            // Then
            assertFalse(deleted)
        }

    @Test
    fun `getFileMetadata should return file metadata`() =
        runTest {
            // Given
            val fileName = "metadata-test.txt"
            val purpose = "assistants"
            val content = "Test content for metadata"
            val file =
                MockMultipartFile(
                    "file",
                    fileName,
                    MediaType.TEXT_PLAIN_VALUE,
                    content.toByteArray(),
                ).toFilePart()
            val fileId = fileStorageService.store(file, purpose)

            // When
            val metadata = fileStorageService.getFileMetadata(fileId)

            // Then
            assertEquals(fileId, metadata["id"])
            assertEquals(fileName, metadata["filename"])
            assertEquals(purpose, metadata["purpose"])
            assertEquals(content.toByteArray().size.toLong(), metadata["bytes"])
            assertTrue(metadata.containsKey("created_at"))
        }

    @Test
    fun `exists should return true for existing file`() =
        runTest {
            // Given
            val file =
                MockMultipartFile(
                    "file",
                    "test.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Test content".toByteArray(),
                ).toFilePart()
            val fileId = fileStorageService.store(file, "assistants")

            // When
            val exists = fileStorageService.exists(fileId)

            // Then
            assertTrue(exists)
        }

    @Test
    fun `exists should return false for non-existent file`() =
        runTest {
            // When
            val exists = fileStorageService.exists("non-existent-file")

            // Then
            assertFalse(exists)
        }

    @Test
    fun `registerPostProcessHook should register hook function`() =
        runTest {
            // Given
            var hookCalled = false
            var hookFileId = ""
            var hookPurpose = ""

            fileStorageService.registerPostProcessHook { fileId, purpose ->
                hookCalled = true
                hookFileId = fileId
                hookPurpose = purpose
            }

            // When
            val file =
                MockMultipartFile(
                    "file",
                    "hook-test.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Testing hooks".toByteArray(),
                ).toFilePart()
            val fileId = fileStorageService.store(file, "assistants")

            // Then
            assertTrue(hookCalled)
            assertEquals(fileId, hookFileId)
            assertEquals("assistants", hookPurpose)
        }
}
