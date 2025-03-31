package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.FilePurpose
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorIndexingTest {
    private lateinit var fileService: FileService
    private lateinit var fileStorageService: FileStorageService
    private lateinit var vectorSearchProvider: MockVectorSearchProvider
    private lateinit var testFile: MultipartFile
    private val fileContent = "This is test content for vector indexing"
    private var postProcessHook: ((String, String) -> Unit)? = null

    @BeforeEach
    fun setUp() {
        fileStorageService = mockk()
        vectorSearchProvider = MockVectorSearchProvider()

        // Clear any previous hook
        postProcessHook = null

        // Capture the post process hook without invoking it immediately
        every { fileStorageService.registerPostProcessHook(any()) } answers {
            postProcessHook = firstArg<(String, String) -> Unit>()
        }

        fileService = FileService(fileStorageService, vectorSearchProvider)

        testFile =
            MockMultipartFile(
                "file",
                "test-file.txt",
                MediaType.TEXT_PLAIN_VALUE,
                fileContent.toByteArray(),
            )

        // Simulate storing the file and trigger the hook if the file purpose is 'assistants'
        every { fileStorageService.store(any(), any()) } answers {
            val storedId = "test-id"
            // Retrieve metadata for the stored file (the test sets this up per case)
            val metadata = fileStorageService.getFileMetadata(storedId)
            if ((metadata["purpose"] as? String) == "assistants") {
                postProcessHook?.invoke(storedId, metadata["purpose"] as String)
            }
            storedId
        }

        val resource = spyk(InputStreamResource(ByteArrayInputStream(fileContent.toByteArray())))

        every { resource.filename } returns "test-file.txt"

        every {
            fileStorageService.loadAsResource(any())
        } returns resource
    }

    @Test
    fun `file should be indexed when uploaded with assistants purpose`() {
        // Given
        val metadataMap =
            mutableMapOf<String, Any>(
                "id" to "test-id",
                "filename" to "test-file.txt",
                "purpose" to "assistants",
                "bytes" to fileContent.toByteArray().size.toLong(),
                "created_at" to System.currentTimeMillis() / 1000,
            )

        every { fileStorageService.getFileMetadata(any()) } returns metadataMap

        // When
        val uploadedFile = fileService.uploadFile(testFile, FilePurpose.assistants.name)

        // Then
        assertEquals(FilePurpose.assistants.name, uploadedFile.purpose)
        val indexedFiles = vectorSearchProvider.getIndexedFiles()
        assertEquals(1, indexedFiles.size)
        assertTrue(indexedFiles.containsKey(uploadedFile.id))
        assertEquals("test-file.txt", indexedFiles[uploadedFile.id]?.filename)
        assertEquals(fileContent, indexedFiles[uploadedFile.id]?.content)
    }

    @Test
    fun `file should not be indexed when uploaded with non-assistants purpose`() {
        // Given
        val metadataMap =
            mutableMapOf<String, Any>(
                "id" to "test-id",
                "filename" to "test-file.txt",
                "purpose" to "fine_tune",
                "bytes" to fileContent.toByteArray().size.toLong(),
                "created_at" to System.currentTimeMillis() / 1000,
            )

        every { fileStorageService.getFileMetadata(any()) } returns metadataMap

        // When
        val uploadedFile = fileService.uploadFile(testFile, FilePurpose.fine_tune.name)

        // Then
        assertEquals(FilePurpose.fine_tune.name, uploadedFile.purpose)
        val indexedFiles = vectorSearchProvider.getIndexedFiles()
        assertEquals(0, indexedFiles.size)
    }

    @Test
    fun `deleted file should be removed from index`() {
        // Given
        val metadataMap =
            mutableMapOf<String, Any>(
                "id" to "test-id",
                "filename" to "test-file.txt",
                "purpose" to "assistants",
                "bytes" to fileContent.toByteArray().size.toLong(),
                "created_at" to System.currentTimeMillis() / 1000,
            )

        every { fileStorageService.getFileMetadata(any()) } returns metadataMap
        every { fileStorageService.delete(any()) } returns true
        every { fileStorageService.exists(any()) } returns true

        // When - upload and index file
        val uploadedFile = fileService.uploadFile(testFile, FilePurpose.assistants.name)

        // Index the file manually since the mock doesn't actually call the hook
        vectorSearchProvider.indexFile(
            uploadedFile.id,
            ByteArrayInputStream(fileContent.toByteArray()),
            uploadedFile.filename,
        )

        // Then - verify indexed
        assertEquals(1, vectorSearchProvider.getIndexedFiles().size)

        // When - delete file
        fileService.deleteFile(uploadedFile.id)

        // Then - verify removed from index
        assertEquals(0, vectorSearchProvider.getIndexedFiles().size)
    }

    @Test
    fun `search functionality should use vector search provider`() {
        // Given
        // Create and index some test files
        val file1Content = "This document contains information about apples"
        val file2Content = "This document contains information about bananas"

        val file1 = MockMultipartFile("file", "file1.txt", MediaType.TEXT_PLAIN_VALUE, file1Content.toByteArray())
        val file2 = MockMultipartFile("file", "file2.txt", MediaType.TEXT_PLAIN_VALUE, file2Content.toByteArray())

        // Set up metadata for file1
        val metadataMap1 =
            mutableMapOf<String, Any>(
                "id" to "file-1",
                "filename" to "file1.txt",
                "purpose" to "assistants",
                "bytes" to file1Content.toByteArray().size.toLong(),
                "created_at" to System.currentTimeMillis() / 1000,
            )

        // Set up metadata for file2
        val metadataMap2 =
            mutableMapOf<String, Any>(
                "id" to "file-2",
                "filename" to "file2.txt",
                "purpose" to "assistants",
                "bytes" to file2Content.toByteArray().size.toLong(),
                "created_at" to System.currentTimeMillis() / 1000,
            )

        // Set up mocks for file1
        every {
            fileStorageService.store(match { it.originalFilename == "file1.txt" }, any())
        } returns "file-1"

        every {
            fileStorageService.loadAsResource(eq("file-1"))
        } returns InputStreamResource(ByteArrayInputStream(file1Content.toByteArray()))

        every {
            fileStorageService.getFileMetadata(eq("file-1"))
        } returns metadataMap1

        // Set up mocks for file2
        every {
            fileStorageService.store(match { it.originalFilename == "file2.txt" }, any())
        } returns "file-2"

        every {
            fileStorageService.loadAsResource(eq("file-2"))
        } returns InputStreamResource(ByteArrayInputStream(file2Content.toByteArray()))

        every {
            fileStorageService.getFileMetadata(eq("file-2"))
        } returns metadataMap2

        // Upload files
        val uploadedFile1 = fileService.uploadFile(file1, FilePurpose.assistants.name)
        val uploadedFile2 = fileService.uploadFile(file2, FilePurpose.assistants.name)

        // Index files manually since the mock doesn't actually call the hook
        vectorSearchProvider.indexFile(
            uploadedFile1.id,
            ByteArrayInputStream(file1Content.toByteArray()),
            uploadedFile1.filename,
        )

        vectorSearchProvider.indexFile(
            uploadedFile2.id,
            ByteArrayInputStream(file2Content.toByteArray()),
            uploadedFile2.filename,
        )

        // When - search for files with the query "apples"
        val results = vectorSearchProvider.searchSimilar("apples", 10)

        // Then - verify results
        assertEquals(1, results.size)
        assertEquals("file-1", results.first().fileId)
    }
}
