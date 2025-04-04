package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.FileCounts
import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@DataMongoTest
@ExtendWith(SpringExtension::class)
@Import(MongoVectorStoreRepository::class)
@TestPropertySource(properties = ["open-responses.vector-store.repository.type=mongodb"])
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

    // Helper methods
    private fun createTestVectorStore(
        id: String = "vs_" + UUID.randomUUID().toString(),
        name: String = "Test Vector Store",
        createdAt: Long = Instant.now().epochSecond,
    ): VectorStore =
        VectorStore(
            id = id,
            name = name,
            createdAt = createdAt,
            lastActiveAt = createdAt,
            fileCounts = FileCounts(total = 0, completed = 0),
            metadata = mapOf("key" to "value"),
        )

    private fun createTestVectorStoreFile(
        id: String = "file_" + UUID.randomUUID().toString(),
        vectorStoreId: String,
        status: String = "completed",
        createdAt: Long = Instant.now().epochSecond,
    ): VectorStoreFile =
        VectorStoreFile(
            id = id,
            vectorStoreId = vectorStoreId,
            status = status,
            createdAt = createdAt,
        )
} 
