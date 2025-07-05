package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.FileCounts
import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.dropCollection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@DataMongoTest
@ExtendWith(SpringExtension::class)
@Import(MongoVectorStoreRepository::class)
@TestPropertySource(properties = ["open-responses.store.vector.repository.type=mongodb"])
@Disabled("Enable this test to run the complete workflow") // Disable this line to run the test
class MongoVectorStoreRepositoryTest {
    companion object {
        @Container
        private val mongoDBContainer =
            MongoDBContainer(DockerImageName.parse("mongo:5.0.15"))
                .withExposedPorts(27017)

        @JvmStatic
        @DynamicPropertySource
        fun setProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") { 
                "mongodb://${mongoDBContainer.host}:${mongoDBContainer.firstMappedPort}/testdb" 
            }
        }
    }

    @Autowired
    private lateinit var vectorStoreRepository: MongoVectorStoreRepository

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @BeforeEach
    fun setup() {
        // Initialize collections if needed
    }

    @AfterEach
    fun cleanup() =
        runTest {
            // Clean up collections after each test
            mongoTemplate.dropCollection<VectorStore>().block()
            mongoTemplate.dropCollection<VectorStoreFile>().block()
        }

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
            assertEquals(vectorStore.name, savedVectorStore.name)
        
            // Verify it was saved in the database
            val foundVectorStore = vectorStoreRepository.findVectorStoreById(vectorStore.id)
            assertNotNull(foundVectorStore)
            assertEquals(vectorStore.id, foundVectorStore.id)
        }

    @Test
    fun `findVectorStoreById should return null for non-existent ID`() =
        runTest {
            // When
            val result = vectorStoreRepository.findVectorStoreById("non-existent-id")
        
            // Then
            assertNull(result)
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
            assertEquals(3, result.size)
            // Should be sorted by createdAt in descending order
            assertEquals(vectorStore3.id, result[0].id)
            assertEquals(vectorStore2.id, result[1].id)
            assertEquals(vectorStore1.id, result[2].id)
        }

    @Test
    fun `listVectorStores should respect pagination parameters`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(name = "Test Store 1", createdAt = Instant.now().epochSecond - 100)
            val vectorStore2 = createTestVectorStore(name = "Test Store 2", createdAt = Instant.now().epochSecond - 50)
            val vectorStore3 = createTestVectorStore(name = "Test Store 3", createdAt = Instant.now().epochSecond)
        
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
        
            // When - get results after vectorStore3 (which is first when sorted by desc)
            val result = vectorStoreRepository.listVectorStores(limit = 10, after = vectorStore3.id)
        
            // Then
            assertEquals(2, result.size)
            assertEquals(vectorStore2.id, result[0].id)
            assertEquals(vectorStore1.id, result[1].id)
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
            assertTrue(result)
        
            // Verify vector store was deleted
            val deletedVectorStore = vectorStoreRepository.findVectorStoreById(vectorStore.id)
            assertNull(deletedVectorStore)
        
            // Verify vector store file was deleted
            val deletedVectorStoreFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, vectorStoreFile.id)
            assertNull(deletedVectorStoreFile)
        }

    @Test
    fun `saveVectorStoreFile should save a vector store file`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id)
        
            vectorStoreRepository.saveVectorStore(vectorStore)
        
            // When
            val savedVectorStoreFile = vectorStoreRepository.saveVectorStoreFile(vectorStoreFile)
        
            // Then
            assertNotNull(savedVectorStoreFile)
            assertEquals(vectorStoreFile.id, savedVectorStoreFile.id)
        
            // Verify it was saved in the database
            val foundVectorStoreFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, vectorStoreFile.id)
            assertNotNull(foundVectorStoreFile)
            assertEquals(vectorStoreFile.id, foundVectorStoreFile.id)
        }

    @Test
    fun `findVectorStoreFileById should return null for non-existent ID`() =
        runTest {
            // When
            val result = vectorStoreRepository.findVectorStoreFileById("non-existent-store", "non-existent-file")
        
            // Then
            assertNull(result)
        }

    @Test
    fun `listVectorStoreFiles should return all files for a vector store`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile1 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id, 
                    status = "completed",
                    createdAt = Instant.now().epochSecond - 100,
                )
            val vectorStoreFile2 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id, 
                    status = "in_progress",
                    createdAt = Instant.now().epochSecond - 50,
                )
            val vectorStoreFile3 =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id, 
                    status = "completed",
                    createdAt = Instant.now().epochSecond,
                )
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile1)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile2)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile3)
        
            // When - default sorting is desc by createdAt
            val result = vectorStoreRepository.listVectorStoreFiles(vectorStoreId = vectorStore.id, limit = 10)
        
            // Then
            assertEquals(3, result.size)
            // Should be sorted by createdAt in descending order
            assertEquals(vectorStoreFile3.id, result[0].id)
            assertEquals(vectorStoreFile2.id, result[1].id)
            assertEquals(vectorStoreFile1.id, result[2].id)
        }

    @Test
    fun `listVectorStoreFiles should filter by status`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val vectorStoreFile1 = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "completed")
            val vectorStoreFile2 = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "in_progress")
            val vectorStoreFile3 = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "completed")
        
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile1)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile2)
            vectorStoreRepository.saveVectorStoreFile(vectorStoreFile3)
        
            // When
            val result =
                vectorStoreRepository.listVectorStoreFiles(
                    vectorStoreId = vectorStore.id, 
                    limit = 10, 
                    filter = "completed",
                )
        
            // Then
            assertEquals(2, result.size)
            // All results should have status "completed"
            assertTrue(result.all { it.status == "completed" })
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
            assertTrue(result)
        
            // Verify vector store file was deleted
            val deletedFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, vectorStoreFile.id)
            assertNull(deletedFile)
        }

    @Test
    fun `listVectorStoreFiles should respect pagination with before parameter`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val files =
                (1..5).map { i ->
                    createTestVectorStoreFile(
                        vectorStoreId = vectorStore.id,
                        id = "file-$i",
                        createdAt = Instant.now().epochSecond + i * 100, // Increasing timestamps
                    )
                }

            vectorStoreRepository.saveVectorStore(vectorStore)
            files.forEach { vectorStoreRepository.saveVectorStoreFile(it) }

            // When - get files before file-4 (should return all with createdAt < file-4)
            val result =
                vectorStoreRepository.listVectorStoreFiles(
                    vectorStoreId = vectorStore.id,
                    before = "file-4",
                    order = "desc",
                )

            // Then - should get file-3, file-2, file-1 (in that order)
            assertEquals(3, result.size, "Should return 3 files")
            assertEquals("file-3", result[0].id, "First result should be file-3")
            assertEquals("file-2", result[1].id, "Second result should be file-2")
            assertEquals("file-1", result[2].id, "Third result should be file-1")
        }

    @Test
    fun `listVectorStores should support sorting by different orders`() =
        runTest {
            // Given
            val vectorStore1 = createTestVectorStore(name = "Test Store 1", createdAt = Instant.now().epochSecond - 100)
            val vectorStore2 = createTestVectorStore(name = "Test Store 2", createdAt = Instant.now().epochSecond - 50)
            val vectorStore3 = createTestVectorStore(name = "Test Store 3", createdAt = Instant.now().epochSecond)
            
            vectorStoreRepository.saveVectorStore(vectorStore1)
            vectorStoreRepository.saveVectorStore(vectorStore2)
            vectorStoreRepository.saveVectorStore(vectorStore3)
            
            // When - get in ascending order by creation time
            val result = vectorStoreRepository.listVectorStores(order = "asc")
            
            // Then
            assertEquals(3, result.size, "Should return all 3 vector stores")
            assertEquals(vectorStore1.id, result[0].id, "First result should be oldest vector store")
            assertEquals(vectorStore2.id, result[1].id, "Second result should be middle-aged vector store")
            assertEquals(vectorStore3.id, result[2].id, "Third result should be newest vector store")
        }

    @Test
    fun `listVectorStoreFiles should filter by status correctly`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val pendingFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "pending")
            val inProgressFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "in_progress")
            val completedFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "completed")
            val errorFile = createTestVectorStoreFile(vectorStoreId = vectorStore.id, status = "error")
            
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(pendingFile)
            vectorStoreRepository.saveVectorStoreFile(inProgressFile)
            vectorStoreRepository.saveVectorStoreFile(completedFile)
            vectorStoreRepository.saveVectorStoreFile(errorFile)
            
            // When - filter for completed files only
            val completedFiles =
                vectorStoreRepository.listVectorStoreFiles(
                    vectorStoreId = vectorStore.id,
                    filter = "completed",
                )
            
            // Then
            assertEquals(1, completedFiles.size, "Should return only the completed file")
            assertEquals(completedFile.id, completedFiles[0].id, "Should return the correct completed file")
            
            // When - filter for in_progress files
            val inProgressFiles =
                vectorStoreRepository.listVectorStoreFiles(
                    vectorStoreId = vectorStore.id,
                    filter = "in_progress",
                )
            
            // Then
            assertEquals(1, inProgressFiles.size, "Should return only the in_progress file")
            assertEquals(inProgressFile.id, inProgressFiles[0].id, "Should return the correct in_progress file")
        }

    @Test
    fun `saveVectorStoreFile should update existing file`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            val file =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "pending",
                )
            
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(file)
            
            // When - update the file status
            val updatedFile = file.copy(status = "completed")
            val result = vectorStoreRepository.saveVectorStoreFile(updatedFile)
            
            // Then
            assertEquals("completed", result.status, "File status should be updated")
            
            // Verify in database
            val retrievedFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, file.id)
            assertNotNull(retrievedFile, "File should exist in database")
            assertEquals("completed", retrievedFile.status, "File status in database should be updated")
        }

    @Test
    fun `deleteVectorStore should handle case when vector store does not exist`() =
        runTest {
            // When
            val result = vectorStoreRepository.deleteVectorStore("non-existent-id")
            
            // Then
            assertFalse(result, "Should return false when vector store doesn't exist")
        }

    @Test
    fun `deleteVectorStoreFile should handle case when file does not exist`() =
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
    fun `listVectorStoreFiles should handle case when vector store does not exist`() =
        runTest {
            // When
            val result = vectorStoreRepository.listVectorStoreFiles("non-existent-store")
            
            // Then
            assertTrue(result.isEmpty(), "Should return empty list when vector store doesn't exist")
        }

    @Test
    fun `saveVectorStore should update existing vector store`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore(name = "Original Name")
            vectorStoreRepository.saveVectorStore(vectorStore)
            
            // When - update the name
            val updatedVectorStore = vectorStore.copy(name = "Updated Name")
            val result = vectorStoreRepository.saveVectorStore(updatedVectorStore)
            
            // Then
            assertEquals("Updated Name", result.name, "Vector store name should be updated")
            
            // Verify in database
            val retrievedVectorStore = vectorStoreRepository.findVectorStoreById(vectorStore.id)
            assertNotNull(retrievedVectorStore, "Vector store should exist in database")
            assertEquals("Updated Name", retrievedVectorStore.name, "Vector store name in database should be updated")
        }

    @Test
    fun `listVectorStores should handle multiple page navigation`() =
        runTest {
            // Given - 10 vector stores
            val stores =
                (1..10).map { i ->
                    createTestVectorStore(
                        name = "Store $i",
                        createdAt = Instant.now().epochSecond + i * 100, // Increasing timestamps
                    )
                }
            
            stores.forEach { vectorStoreRepository.saveVectorStore(it) }
            
            // When - get first page (3 items)
            val page1 = vectorStoreRepository.listVectorStores(limit = 3)
            
            // Then
            assertEquals(3, page1.size, "First page should have 3 items")
            assertEquals(stores[9].id, page1[0].id, "First item should be newest")
            
            // When - get second page
            val page2 = vectorStoreRepository.listVectorStores(limit = 3, after = page1.last().id)
            
            // Then
            assertEquals(3, page2.size, "Second page should have 3 items")
            assertEquals(stores[6].id, page2[0].id, "First item of second page should be correct")
            
            // When - get third page
            val page3 = vectorStoreRepository.listVectorStores(limit = 3, after = page2.last().id)
            
            // Then
            assertEquals(3, page3.size, "Third page should have 3 items")
            
            // When - get fourth page (should only have 1 item left)
            val page4 = vectorStoreRepository.listVectorStores(limit = 3, after = page3.last().id)
            
            // Then
            assertEquals(1, page4.size, "Fourth page should have 1 item")
            assertEquals(stores[0].id, page4[0].id, "Last item should be oldest")
        }

    @Test
    fun `simulated file status transition from pending to completed`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            // Start with a pending file
            val pendingFile =
                createTestVectorStoreFile(
                    vectorStoreId = vectorStore.id,
                    status = "pending",
                )
            
            vectorStoreRepository.saveVectorStore(vectorStore)
            vectorStoreRepository.saveVectorStoreFile(pendingFile)
            
            // When - update to in_progress
            val inProgressFile = pendingFile.copy(status = "in_progress")
            vectorStoreRepository.saveVectorStoreFile(inProgressFile)
            
            // Then
            val retrievedInProgressFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, pendingFile.id)
            assertNotNull(retrievedInProgressFile, "File should exist in database")
            assertEquals("in_progress", retrievedInProgressFile.status, "File status should be updated to in_progress")
            
            // When - update to completed with metadata
            val completedFile =
                inProgressFile.copy(
                    status = "completed",
                    attributes = mapOf("chunks" to 10, "vectorized" to true),
                )
            vectorStoreRepository.saveVectorStoreFile(completedFile)
            
            // Then
            val retrievedCompletedFile = vectorStoreRepository.findVectorStoreFileById(vectorStore.id, pendingFile.id)
            assertNotNull(retrievedCompletedFile, "File should exist in database")
            assertEquals("completed", retrievedCompletedFile.status, "File status should be updated to completed")
            assertEquals(10, retrievedCompletedFile.attributes?.get("chunks"), "Metadata should be updated")
            assertEquals(true, retrievedCompletedFile.attributes?.get("vectorized"), "Metadata should be updated")
        }

    /**
     * Helper function to create a test vector store.
     */
    private fun createTestVectorStore(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Vector Store",
        description: String = "Test description",
        createdAt: Long = Instant.now().epochSecond,
    ): VectorStore =
        VectorStore(
            id = id,
            name = name,
            createdAt = createdAt,
            fileCounts = FileCounts(total = 0, inProgress = 0, completed = 0, failed = 0),
        )

    /**
     * Helper function to create a test vector store file.
     */
    private fun createTestVectorStoreFile(
        id: String = UUID.randomUUID().toString(),
        vectorStoreId: String,
        filename: String = "test.txt",
        fileId: String = UUID.randomUUID().toString(),
        status: String = "pending",
        createdAt: Long = Instant.now().epochSecond,
        metadata: Map<String, Any> = emptyMap(),
    ): VectorStoreFile =
        VectorStoreFile(
            id = id,
            vectorStoreId = vectorStoreId,
            status = status,
            createdAt = createdAt,
            attributes =
                metadata.toMutableMap() +
                    mapOf(
                        "filename" to filename,
                        "file_id" to fileId,
                    ),
        )
} 
