package ai.masaic.openresponses.api.integration

import ai.masaic.openresponses.api.model.FilePurpose
import ai.masaic.openresponses.api.service.FileService
import ai.masaic.openresponses.api.service.LocalFileStorageService
import ai.masaic.openresponses.api.service.MockVectorSearchProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the file upload and search functionality.
 * Tests the integration between FileService, LocalFileStorageService and VectorSearchProvider.
 */
class FileUploadAndSearchIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var localFileStorageService: LocalFileStorageService
    private lateinit var vectorSearchProvider: MockVectorSearchProvider
    private lateinit var fileService: FileService

    @BeforeEach
    fun setUp() =
        runTest {
            // Set tempDir as root storage location
            localFileStorageService = LocalFileStorageService(tempDir.toString())

            vectorSearchProvider = MockVectorSearchProvider()
            fileService = FileService(localFileStorageService, vectorSearchProvider)
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
    fun `should store and retrieve files correctly`() =
        runTest {
            // Given - create test files with different content
            val file1 =
                MockMultipartFile(
                    "file",
                    "machine-learning.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "This document is about machine learning and AI technologies.".toByteArray(),
                )

            val file2 =
                MockMultipartFile(
                    "file",
                    "database-systems.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "This document describes database management systems and SQL.".toByteArray(),
                )

            val file3 =
                MockMultipartFile(
                    "file",
                    "programming.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "This document covers programming languages like Java, Python, and Kotlin.".toByteArray(),
                )

            // When - upload files
            val uploadedFile1 = fileService.uploadFile(file1, FilePurpose.assistants.name)
            val uploadedFile2 = fileService.uploadFile(file2, FilePurpose.assistants.name)
            val uploadedFile3 = fileService.uploadFile(file3, FilePurpose.assistants.name)

            // Then - verify files were stored correctly
            assertEquals("machine-learning.txt", uploadedFile1.filename)
            assertEquals(FilePurpose.assistants.name, uploadedFile1.purpose)
            assertTrue(uploadedFile1.id.startsWith("file-"))

            // When - list all files
            val allFiles = fileService.listFiles().data
        
            // Then - verify all files are listed
            assertEquals(3, allFiles.size)
            assertTrue(allFiles.any { it.id == uploadedFile1.id })
            assertTrue(allFiles.any { it.id == uploadedFile2.id })
            assertTrue(allFiles.any { it.id == uploadedFile3.id })

            // When - perform vector search for "machine learning"
            val searchResults = vectorSearchProvider.searchSimilar("machine learning", 5)
        
            // Then - verify search returns relevant document
            assertEquals(1, searchResults.size)
            assertEquals(uploadedFile1.id, searchResults[0].fileId)
        
            // When - perform vector search for "database"
            val databaseResults = vectorSearchProvider.searchSimilar("database", 5)
        
            // Then - verify search returns relevant document
            assertEquals(1, databaseResults.size)
            assertEquals(uploadedFile2.id, databaseResults[0].fileId)
        
            // When - delete a file
            val deleteResult = fileService.deleteFile(uploadedFile1.id)
        
            // Then - verify file was deleted
            assertTrue(deleteResult.deleted)
        
            // When - list files after deletion
            val remainingFiles = fileService.listFiles().data
        
            // Then - verify file count has decreased
            assertEquals(2, remainingFiles.size)
            assertTrue(remainingFiles.none { it.id == uploadedFile1.id })
        
            // When - search for deleted content
            val newSearchResults = vectorSearchProvider.searchSimilar("machine learning", 5)
        
            // Then - verify deleted file is no longer in search results
            assertEquals(0, newSearchResults.size)
        }

    @Test
    fun `should handle custom file purpose correctly`() =
        runTest {
            // Given - files with different purposes
            val aiFile =
                MockMultipartFile(
                    "file",
                    "ai-doc.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "This is a document about artificial intelligence.".toByteArray(),
                )
        
            val tuningFile =
                MockMultipartFile(
                    "file",
                    "tuning-doc.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "This is a document for fine-tuning models.".toByteArray(),
                )
        
            // When - upload files with different purposes
            val aiFileUploaded = fileService.uploadFile(aiFile, FilePurpose.assistants.name)
            val tuningFileUploaded = fileService.uploadFile(tuningFile, FilePurpose.fine_tune.name)
        
            // Then - verify purposes are set correctly
            assertEquals(FilePurpose.assistants.name, aiFileUploaded.purpose)
            assertEquals(FilePurpose.fine_tune.name, tuningFileUploaded.purpose)
        
            // When - list files by purpose
            val assistantsFiles = fileService.listFiles(purpose = FilePurpose.assistants.name).data
            val tuningFiles = fileService.listFiles(purpose = FilePurpose.fine_tune.name).data
        
            // Then - verify filtering works correctly
            assertEquals(1, assistantsFiles.size)
            assertEquals(aiFileUploaded.id, assistantsFiles[0].id)
        
            assertEquals(1, tuningFiles.size)
            assertEquals(tuningFileUploaded.id, tuningFiles[0].id)
        
            // When - search for documents
            val aiSearchResults = vectorSearchProvider.searchSimilar("artificial intelligence", 5)
        
            // Then - only the ASSISTANTS purpose file should be indexed
            assertEquals(1, aiSearchResults.size)
            assertEquals(aiFileUploaded.id, aiSearchResults[0].fileId)
        
            val tuningSearchResults = vectorSearchProvider.searchSimilar("fine-tuning", 5)
        
            // Then - fine-tune files should not be indexed
            assertEquals(0, tuningSearchResults.size)
        }
} 
