package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.FileCounts
import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(SpringExtension::class)
class FileBasedVectorStoreRepositoryTest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var objectMapper: ObjectMapper
    private lateinit var vectorStoreRepository: FileBasedVectorStoreRepository

    @BeforeEach
    fun setup() {
        // Use a real ObjectMapper
        objectMapper = jacksonObjectMapper()
        
        // Create the repository with a temp directory
        vectorStoreRepository = FileBasedVectorStoreRepository(tempDir.toString(), objectMapper)
    }

    @AfterEach
    fun cleanup() {
        // Clean up any files created during tests
        val vectorStoresDir = tempDir.resolve("vector_stores")
        val vectorStoreFilesDir = tempDir.resolve("vector_store_files")
        
        if (Files.exists(vectorStoresDir)) {
            Files.list(vectorStoresDir).forEach { Files.deleteIfExists(it) }
        }
        
        if (Files.exists(vectorStoreFilesDir)) {
            Files.list(vectorStoreFilesDir).forEach { Files.deleteIfExists(it) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `saveVectorStore should save a vector store`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()

            // When
            val savedVectorStore = vectorStoreRepository.saveVectorStore(vectorStore)

            // Then
            assertNotNull(savedVectorStore)
            assertEquals(vectorStore.id, savedVectorStore.id)
        
            // Verify file exists on disk
            val filePath = tempDir.resolve("vector_stores/${vectorStore.id}.json")
            assertTrue(Files.exists(filePath), "Vector store metadata file should exist")
        }

    @Test
    fun `findVectorStoreById should return null for non-existent ID`() =
        runTest {
            // When
            val result = vectorStoreRepository.findVectorStoreById("non-existent-id")
        
            // Then
            assertNull(result, "Should return null for non-existent vector store")
        }

    @Test
    fun `findVectorStoreById should return vector store for existing ID`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            vectorStoreRepository.saveVectorStore(vectorStore)
        
            // When
            val result = vectorStoreRepository.findVectorStoreById(vectorStore.id)
        
            // Then
            assertNotNull(result, "Should return vector store for existing ID")
            assertEquals(vectorStore.id, result.id, "Should return correct vector store")
            assertEquals(vectorStore.name, result.name, "Should return vector store with correct name")
        }

    @Test
    fun `listVectorStores should return all vector stores`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(name = "Test Store 1", createdAt = Instant.now().epochSecond - 100)
            val vectorStore2 = createTestVectorStore(name = "Test Store 2", createdAt = Instant.now().epochSecond - 50)
            val vectorStore3 = createTestVectorStore(name = "Test Store 3", createdAt = Instant.now().epochSecond)
        
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
        
            // When - default sorting is desc by createdAt
            val result = vectorStoreRepository.listVectorStores(limit = 10)
        
            // Then
            assertEquals(3, result.size, "Should return all vector stores")
            // Should be sorted by createdAt in descending order
            assertEquals(vectorStore3.id, result[0].id, "First result should be newest")
            assertEquals(vectorStore2.id, result[1].id, "Second result should be second newest")
            assertEquals(vectorStore1.id, result[2].id, "Third result should be oldest")
        }

    @Test
    fun `listVectorStores should respect limit parameter`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(createdAt = Instant.now().epochSecond - 200)
            val vectorStore2 = createTestVectorStore(createdAt = Instant.now().epochSecond - 100)
            val vectorStore3 = createTestVectorStore(createdAt = Instant.now().epochSecond)
        
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
        
            // When
            val result = vectorStoreRepository.listVectorStores(limit = 2)
        
            // Then
            assertEquals(2, result.size, "Should return limited number of vector stores")
            // Should be sorted by createdAt in descending order
            assertEquals(vectorStore3.id, result[0].id, "First result should be newest")
            assertEquals(vectorStore2.id, result[1].id, "Second result should be second newest")
        }

    @Test
    fun `listVectorStores should respect after parameter`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(createdAt = Instant.now().epochSecond - 200)
            val vectorStore2 = createTestVectorStore(createdAt = Instant.now().epochSecond - 100)
            val vectorStore3 = createTestVectorStore(createdAt = Instant.now().epochSecond)
        
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
        
            // When - get results after vectorStore3 (which is first when sorted by desc)
            val result = vectorStoreRepository.listVectorStores(limit = 10, after = vectorStore3.id)
        
            // Then
            assertEquals(2, result.size, "Should return vector stores after specified ID")
            assertEquals(vectorStore2.id, result[0].id, "First result should be correct")
            assertEquals(vectorStore1.id, result[1].id, "Second result should be correct")
        }

    @Test
    fun `listVectorStores should respect before parameter`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(createdAt = Instant.now().epochSecond - 200)
            val vectorStore2 = createTestVectorStore(createdAt = Instant.now().epochSecond - 100)
            val vectorStore3 = createTestVectorStore(createdAt = Instant.now().epochSecond)
        
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
        
            // When - get results before vectorStore1 (which is last when sorted by desc)
            val result = vectorStoreRepository.listVectorStores(limit = 10, before = vectorStore1.id)
        
            // Then
            assertEquals(2, result.size, "Should return vector stores before specified ID")
            assertEquals(vectorStore3.id, result[0].id, "First result should be correct")
            assertEquals(vectorStore2.id, result[1].id, "Second result should be correct")
        }

    @Test
    fun `listVectorStores should respect order parameter`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(name = "Test Store 1", createdAt = Instant.now().epochSecond - 200)
            val vectorStore2 = createTestVectorStore(name = "Test Store 2", createdAt = Instant.now().epochSecond - 100)
            val vectorStore3 = createTestVectorStore(name = "Test Store 3", createdAt = Instant.now().epochSecond)
        
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
        
            // When - get in ascending order
            val result = vectorStoreRepository.listVectorStores(limit = 10, order = "asc")
        
            // Then
            assertEquals(3, result.size, "Should return all vector stores")
            // Should be sorted by createdAt in ascending order
            assertEquals(vectorStore1.id, result[0].id, "First result should be oldest")
            assertEquals(vectorStore2.id, result[1].id, "Second result should be second oldest")
            assertEquals(vectorStore3.id, result[2].id, "Third result should be newest")
        }

    @Test
    fun `deleteVectorStore should delete a vector store and its files`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id)
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
        
            // When
            val result = vectorStoreRepository.deleteVectorStore(vectorStore.id)
        
            // Then
            assertTrue(result, "Should return true when vector store is deleted")
        
            // Verify vector store file was deleted
            val vectorStoreFilePath = tempDir.resolve("vector_store_files/${vectorStore.id}-${vectorStoreFile.id}.json")
            assertFalse(Files.exists(vectorStoreFilePath), "Vector store file metadata should be deleted")
        
            // Verify vector store was deleted
            val vectorStorePath = tempDir.resolve("vector_stores/${vectorStore.id}.json")
            assertFalse(Files.exists(vectorStorePath), "Vector store metadata should be deleted")
        }

    @Test
    fun `deleteVectorStore should return false for non-existent ID`() =
        runTest {
            // When
            val result = vectorStoreRepository.deleteVectorStore("non-existent-id")
        
            // Then
            assertFalse(result, "Should return false when vector store doesn't exist")
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `saveVectorStoreFile should save a vector store file`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id)
        
            vectorStoreRepository.saveVectorStore(vectorStore)
        
            // When
            val savedVectorStoreFile = vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)

            advanceUntilIdle()
            // Then
            assertNotNull(savedVectorStoreFile)
            assertEquals(vectorStoreFile.id, savedVectorStoreFile.id)
        
            // Verify file exists on disk
            val filePath = tempDir.resolve("vector_store_files/${vectorStore.id}-${vectorStoreFile.id}.json")
            assertTrue(Files.exists(filePath), "Vector store file metadata should exist")
        }

    @Test
    fun `findVectorStoreFileById should return null for non-existent file ID`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            vectorStoreRepository.saveVectorStore(vectorStore)
        
            // When
            val result = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, "non-existent-file")
        
            // Then
            assertNull(result, "Should return null for non-existent file")
        }

    @Test
    fun `findVectorStoreFileById should return file for existing ID`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id)
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
        
            // When
            val result = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, vectorStoreFile.id)
        
            // Then
            assertNotNull(result, "Should return file for existing ID")
            assertEquals(vectorStoreFile.id, result.id, "Should return correct file")
        }

    @Test
    fun `listVectorStoreFiles should return all files for a vector store`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val file1 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "completed",
                    createdAt = Instant.now().epochSecond - 200,
                )
            val file2 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "pending",
                    createdAt = Instant.now().epochSecond - 100,
                )
            val file3 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "in_progress",
                    createdAt = Instant.now().epochSecond,
                )
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(file1)
            vectorStoreRepository.saveVectorStoreFile(file2)
            vectorStoreRepository.saveVectorStoreFile(file3)
        
            // When - default sorting is desc by createdAt
            val result = vectorStoreRepository.listVectorStoreFiles(vectorStoreId = vectorStore.id)
        
            // Then
            assertEquals(3, result.size, "Should return all files")
            // Should be sorted by createdAt in descending order
            assertEquals(file3.id, result[0].id, "First result should be newest")
            assertEquals(file2.id, result[1].id, "Second result should be second newest")
            assertEquals(file1.id, result[2].id, "Third result should be oldest")
        }

    @Test
    fun `listVectorStoreFiles should filter by status`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val completedFile1 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "completed",
                    createdAt = Instant.now().epochSecond - 200,
                )
            val pendingFile =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "pending",
                    createdAt = Instant.now().epochSecond - 100,
                )
            val completedFile2 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "completed",
                    createdAt = Instant.now().epochSecond,
                )
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(completedFile1)
            vectorStoreRepository.saveVectorStoreFile(pendingFile)
            vectorStoreRepository.saveVectorStoreFile(completedFile2)
        
            // When - filter by completed status
            val result =
                vectorStoreRepository.listVectorStoreFiles(
                    vectorStoreId = vectorStore.id,
                    filter = "completed",
                )
        
            // Then
            assertEquals(2, result.size, "Should return only completed files")
            assertEquals(completedFile2.id, result[0].id, "First result should be newest completed file")
            assertEquals(completedFile1.id, result[1].id, "Second result should be oldest completed file")
        }

    @Test
    fun `deleteVectorStoreFile should delete a vector store file`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id)
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
        
            // When
            val result = vectorStoreRepository.deleteVectorStoreFile(vectorStore.id, vectorStoreFile.id)
        
            // Then
            assertTrue(result, "Should return true when file is deleted")
        
            // Verify file was deleted
            val filePath = tempDir.resolve("vector_store_files/${vectorStore.id}-${vectorStoreFile.id}.json")
            assertFalse(Files.exists(filePath), "Vector store file metadata should be deleted")
        }

    @Test
    fun `deleteVectorStoreFile should return false for non-existent file`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            vectorStoreRepository.saveVectorStore(vectorStore)
        
            // When
            val result = vectorStoreRepository.deleteVectorStoreFile(vectorStore.id, "non-existent-file")
        
            // Then
            assertFalse(result, "Should return false when file doesn't exist")
        }

    @Test
    fun `saveVectorStoreFile should update existing file status`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "pending",
                )
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
        
            // When - update file status
            val updatedFile = vectorStoreFile.copy(status = "completed")
            val result = vectorStoreRepository.saveVectorStoreFile(updatedFile)
        
            // Then
            assertEquals("completed", result.status, "File status should be updated")
        
            // Verify in repository
            val retrievedFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, vectorStoreFile.id)
            assertNotNull(retrievedFile, "File should exist in repository")
            assertEquals("completed", retrievedFile.status, "File status should be updated in repository")
        }

    @Test
    fun `saveVectorStore should update existing vector store`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore(name = "Original Name")
            vectorStoreRepository.saveVectorStore(vectorStore)
        
            // When - update name
            val updatedVectorStore = vectorStore.copy(name = "Updated Name")
            val result = vectorStoreRepository.saveVectorStore(updatedVectorStore)
        
            // Then
            assertEquals("Updated Name", result.name, "Vector store name should be updated")
        
            // Verify in repository
            val retrievedVectorStore = vectorStoreRepository.findVectorStoreById(vectorStore.id)
            assertNotNull(retrievedVectorStore, "Vector store should exist in repository")
            assertEquals("Updated Name", retrievedVectorStore.name, "Vector store name should be updated in repository")
        }

    @Test
    fun `saveVectorStoreFile should update existing file status with metadata`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "in_progress",
                    attributes = mapOf("chunks" to 0, "vectorized" to false),
                )
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
        
            // When - update to completed with metadata
            val completedFile =
                vectorStoreFile.copy(
                    status = "completed",
                    attributes = mapOf("chunks" to 10, "vectorized" to true),
                )
            vectorStoreRepository.saveVectorStoreFile(completedFile)
        
            // Then
            val retrievedCompletedFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, vectorStoreFile.id)
            assertNotNull(retrievedCompletedFile, "File should exist in repository")
            assertEquals("completed", retrievedCompletedFile.status, "File status should be updated to completed")
            assertEquals(10, retrievedCompletedFile.attributes!!["chunks"], "Attributes should be updated")
            assertEquals(true, retrievedCompletedFile.attributes!!["vectorized"], "Attributes should be updated")
        }

    /**
     * Helper function to create a test vector store.
     */
    private fun createTestVectorStore(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Vector Store",
        createdAt: Long = Instant.now().epochSecond,
    ): VectorStore =
        VectorStore(
            id = id,
            name = name,
            createdAt = createdAt,
            fileCounts = FileCounts(total = 0),
            status = "in_progress",
        )

    /**
     * Helper function to create a test vector store file.
     */
    private fun createTestVectorStoreFile(
        id: String = UUID.randomUUID().toString(),
        vectorStoreId: String,
        status: String = "pending",
        createdAt: Long = Instant.now().epochSecond,
        attributes: Map<String, Any> = emptyMap(),
    ): VectorStoreFile =
        VectorStoreFile(
            id = id,
            vectorStoreId = vectorStoreId,
            status = status,
            createdAt = createdAt,
            attributes = attributes,
        )
} 
