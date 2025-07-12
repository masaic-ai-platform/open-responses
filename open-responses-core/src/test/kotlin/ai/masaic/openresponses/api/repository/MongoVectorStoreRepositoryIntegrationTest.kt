package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.config.MongoConfig
import ai.masaic.openresponses.api.model.FileCounts
import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.dropCollection
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [MongoConfig::class, MongoVectorStoreRepository::class])
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = ["open-responses.store.vector.repository.type=mongodb"])
@Disabled("Enable this test to run the complete workflow") // Enable this line to run the test
class MongoVectorStoreRepositoryIntegrationTest {
    companion object {
        @Container
        private val mongoDBContainer =
            MongoDBContainer(DockerImageName.parse("mongo:5.0.15"))
                .withExposedPorts(27017)

        @JvmStatic
        @DynamicPropertySource
        fun setProperties(registry: DynamicPropertyRegistry) {
            registry.add("open-responses.mongodb.uri") {
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
    fun `complete workflow test`() =
        runTest {
            // 1. Create a vector store
            val vectorStore =
                VectorStore(
                    id = "vs_" + UUID.randomUUID().toString(),
                    name = "Integration Test Vector Store",
                    createdAt = Instant.now().epochSecond,
                    lastActiveAt = Instant.now().epochSecond,
                    fileCounts = FileCounts(total = 0, completed = 0),
                    metadata = mapOf("test" to "integration"),
                )
        
            val savedVectorStore = vectorStoreRepository.saveVectorStore(vectorStore)
            assertNotNull(savedVectorStore)
            assertEquals(vectorStore.id, savedVectorStore.id)
        
            // 2. Add files to the vector store
            val file1 =
                VectorStoreFile(
                    id = "file_" + UUID.randomUUID().toString(),
                    vectorStoreId = vectorStore.id,
                    status = "completed",
                    createdAt = Instant.now().epochSecond,
                )
        
            val file2 =
                VectorStoreFile(
                    id = "file_" + UUID.randomUUID().toString(),
                    vectorStoreId = vectorStore.id,
                    status = "in_progress",
                    createdAt = Instant.now().epochSecond + 10,
                )
        
            val savedFile1 = vectorStoreRepository.saveVectorStoreFile(file1)
            val savedFile2 = vectorStoreRepository.saveVectorStoreFile(file2)
        
            assertNotNull(savedFile1)
            assertNotNull(savedFile2)
        
            // 3. List files in the vector store
            val files = vectorStoreRepository.listVectorStoreFiles(vectorStore.id)
            assertEquals(2, files.size)
        
            // 4. Filter files by status
            val completedFiles =
                vectorStoreRepository.listVectorStoreFiles(
                    vectorStoreId = vectorStore.id,
                    filter = "completed",
                )
            assertEquals(1, completedFiles.size)
            assertEquals("completed", completedFiles[0].status)
        
            // 5. Delete a file
            val deleteResult = vectorStoreRepository.deleteVectorStoreFile(vectorStore.id, file1.id)
            assertTrue(deleteResult)
        
            val remainingFiles = vectorStoreRepository.listVectorStoreFiles(vectorStore.id)
            assertEquals(1, remainingFiles.size)
            assertEquals(file2.id, remainingFiles[0].id)
        
            // 6. List all vector stores
            val allStores = vectorStoreRepository.listVectorStores()
            assertTrue(allStores.isNotEmpty())
            assertTrue(allStores.any { it.id == vectorStore.id })
        
            // 7. Delete the vector store and verify cascading delete of files
            val storeDeleteResult = vectorStoreRepository.deleteVectorStore(vectorStore.id)
            assertTrue(storeDeleteResult)
        
            val filesAfterStoreDeletion = vectorStoreRepository.listVectorStoreFiles(vectorStore.id)
            assertTrue(filesAfterStoreDeletion.isEmpty())
        }
} 
