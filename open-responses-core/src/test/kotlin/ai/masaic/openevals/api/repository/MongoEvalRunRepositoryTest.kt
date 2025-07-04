package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.ChatMessage
import ai.masaic.openevals.api.model.CompletionsRunDataSource
import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.EvalRunStatus
import ai.masaic.openevals.api.model.FileDataSource
import ai.masaic.openevals.api.model.ResultCounts
import ai.masaic.openevals.api.model.TemplateInputMessages
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
@Import(MongoEvalRunRepository::class)
@TestPropertySource(properties = ["open-responses.store.type=mongodb"])
@Disabled("Enable this test to run the complete workflow") // Disable this line to run the test
class MongoEvalRunRepositoryTest {
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

        // Define the collection name for tests, matching the one in MongoEvalRunRepository
        const val EVAL_RUN_COLLECTION = "eval_runs" 
    }

    @Autowired
    private lateinit var evalRunRepository: MongoEvalRunRepository

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @BeforeEach
    fun setup() {
        // Make sure we start each test with a clean collection
        try {
            mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            println("Dropped collection for clean test state")
        } catch (e: Exception) {
            println("Collection may not exist yet, which is fine: ${e.message}")
            // This is ok for the first test
        }
    }

    @AfterEach
    fun cleanup() =
        runTest {
            // Clean up collections after each test more thoroughly
            mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            
            // Verify the collection is empty after cleanup
            val query =
                org.springframework.data.mongodb.core.query
                    .Query()
            val count = mongoTemplate.count(query, EvalRun::class.java, MongoEvalRunRepository.EVAL_RUN_COLLECTION).block() ?: 0
            if (count > 0) {
                println("WARNING: Collection still has $count documents after cleanup!")
            }
        }

    // Helper method to create a test EvalRun object
    private fun createTestEvalRun(
        id: String = "run_${UUID.randomUUID().toString().replace("-", "")}",
        evalId: String = "eval_${UUID.randomUUID().toString().replace("-", "")}",
        name: String = "Test Run",
        createdAt: Long = Instant.now().epochSecond,
        status: EvalRunStatus = EvalRunStatus.QUEUED,
        metadata: Map<String, String>? = mapOf("key1" to "value1", "key2" to "value2"),
    ): EvalRun {
        val messages = listOf(ChatMessage("user", "Hello, how are you?"))
        val templateMessages = TemplateInputMessages(template = messages)
        val fileDataSource = FileDataSource(id = "file_12345")
        val completionsRunDataSource =
            CompletionsRunDataSource(
                inputMessages = templateMessages,
                model = "gpt-4",
                source = fileDataSource,
            )
        
        return EvalRun(
            apiKey = "sk-test-123456",
            id = id,
            evalId = evalId,
            name = name,
            createdAt = createdAt,
            dataSource = completionsRunDataSource,
            model = "gpt-4",
            status = status,
            resultCounts = ResultCounts(passed = 0, failed = 0, errored = 0, total = 0),
            metadata = metadata,
        )
    }

    @Test
    fun `createEvalRun should save an eval run with generated ID when ID is blank`() =
        runTest {
            // Given - create eval run with blank ID
            val evalRun = createTestEvalRun(id = "")
            
            // When
            val savedEvalRun = evalRunRepository.createEvalRun(evalRun)
            
            // Then
            assertNotNull(savedEvalRun)
            assertTrue(savedEvalRun.id.startsWith("run_"))
            assertEquals(evalRun.name, savedEvalRun.name)
            
            // Verify it was saved in the database
            val foundEvalRun = evalRunRepository.getEvalRun(savedEvalRun.id)
            assertNotNull(foundEvalRun)
            assertEquals(savedEvalRun.id, foundEvalRun.id)
        }

    @Test
    fun `createEvalRun should save an eval run with provided ID`() =
        runTest {
            // Given
            val runId = "run_${UUID.randomUUID().toString().replace("-", "")}"
            val evalRun = createTestEvalRun(id = runId)
            
            // When
            val savedEvalRun = evalRunRepository.createEvalRun(evalRun)
            
            // Then
            assertNotNull(savedEvalRun)
            assertEquals(runId, savedEvalRun.id)
            assertEquals(evalRun.name, savedEvalRun.name)
            
            // Verify it was saved in the database
            val foundEvalRun = evalRunRepository.getEvalRun(runId)
            assertNotNull(foundEvalRun)
            assertEquals(runId, foundEvalRun.id)
        }

    @Test
    fun `getEvalRun should return null for non-existent ID`() =
        runTest {
            // When
            val result = evalRunRepository.getEvalRun("non-existent-id")
            
            // Then
            assertNull(result)
        }

    @Test
    fun `getEvalRun should return correct eval run for existing ID`() =
        runTest {
            // Given
            val evalRun = createTestEvalRun()
            evalRunRepository.createEvalRun(evalRun)
            
            // When
            val result = evalRunRepository.getEvalRun(evalRun.id)
            
            // Then
            assertNotNull(result)
            assertEquals(evalRun.id, result.id)
            assertEquals(evalRun.name, result.name)
            assertEquals(evalRun.evalId, result.evalId)
            assertEquals(evalRun.metadata, result.metadata)
        }

    @Test
    fun `listEvalRuns should return all eval runs sorted by createdAt descending`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val evalRun1 = createTestEvalRun(name = "Test Run 1", createdAt = time1)
            val evalRun2 = createTestEvalRun(name = "Test Run 2", createdAt = time2)
            val evalRun3 = createTestEvalRun(name = "Test Run 3", createdAt = time3)
            
            // Save eval runs and capture the returned (saved) instances
            val savedEvalRun1 = evalRunRepository.createEvalRun(evalRun1)
            val savedEvalRun2 = evalRunRepository.createEvalRun(evalRun2)
            val savedEvalRun3 = evalRunRepository.createEvalRun(evalRun3)
            
            // Print saved IDs for debugging
            println("Saved eval run IDs: ${savedEvalRun1.id}, ${savedEvalRun2.id}, ${savedEvalRun3.id}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // When - get all eval runs (default is descending order by createdAt)
            val result = evalRunRepository.listEvalRuns()
            
            // Then
            assertEquals(3, result.size, "Expected 3 results")
            
            // Should be sorted by createdAt in descending order (newest first)
            assertEquals(savedEvalRun3.id, result[0].id, "First result should be evalRun3 (newest)")
            assertEquals(savedEvalRun2.id, result[1].id, "Second result should be evalRun2")
            assertEquals(savedEvalRun1.id, result[2].id, "Third result should be evalRun1 (oldest)")
        }

    @Test
    fun `listEvalRunsByEvalId should return runs for a specific eval ID`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            }
            
            // Given - Create 3 runs for the same eval and 1 run for a different eval
            val evalId1 = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            val evalId2 = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            
            val evalRun1 = createTestEvalRun(evalId = evalId1, createdAt = Instant.now().epochSecond - 100)
            val evalRun2 = createTestEvalRun(evalId = evalId1, createdAt = Instant.now().epochSecond - 50)
            val evalRun3 = createTestEvalRun(evalId = evalId1, createdAt = Instant.now().epochSecond)
            val evalRun4 = createTestEvalRun(evalId = evalId2, createdAt = Instant.now().epochSecond - 75)
            
            // Save eval runs
            evalRunRepository.createEvalRun(evalRun1)
            evalRunRepository.createEvalRun(evalRun2)
            evalRunRepository.createEvalRun(evalRun3)
            evalRunRepository.createEvalRun(evalRun4)
            
            // When - get eval runs for evalId1
            val result = evalRunRepository.listEvalRunsByEvalId(evalId1)
            
            // Then
            assertEquals(3, result.size, "Expected 3 results for evalId1")
            assertTrue(result.all { it.evalId == evalId1 }, "All results should have evalId1")
            
            // Should be sorted by createdAt in descending order
            assertEquals(evalRun3.id, result[0].id, "First result should be evalRun3 (newest)")
            assertEquals(evalRun2.id, result[1].id, "Second result should be evalRun2")
            assertEquals(evalRun1.id, result[2].id, "Third result should be evalRun1 (oldest)")
        }

    @Test
    fun `listEvalRunsByEvalId with parameters should respect limit parameter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            }
            
            // Given - Create 3 runs for the same eval
            val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val evalRun1 = createTestEvalRun(evalId = evalId, createdAt = time1)
            val evalRun2 = createTestEvalRun(evalId = evalId, createdAt = time2)
            val evalRun3 = createTestEvalRun(evalId = evalId, createdAt = time3)
            
            // Save eval runs and capture the returned (saved) instances
            val savedEvalRun1 = evalRunRepository.createEvalRun(evalRun1)
            val savedEvalRun2 = evalRunRepository.createEvalRun(evalRun2)
            val savedEvalRun3 = evalRunRepository.createEvalRun(evalRun3)
            
            // Print saved IDs for debugging
            println("Saved eval run IDs: ${savedEvalRun1.id}, ${savedEvalRun2.id}, ${savedEvalRun3.id}")
            
            // When - limit to 2 results (in descending order by default)
            val result = evalRunRepository.listEvalRunsByEvalId(evalId, after = null, limit = 2, order = "desc", status = null)
            
            // Then
            assertEquals(2, result.size, "Expected 2 results due to limit")
            assertEquals(savedEvalRun3.id, result[0].id, "First result should be evalRun3 (newest)")
            assertEquals(savedEvalRun2.id, result[1].id, "Second result should be evalRun2")
        }

    @Test
    fun `listEvalRunsByEvalId with parameters should respect status filter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            }
            
            // Given - Create 3 runs for the same eval with different statuses
            val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            
            val evalRun1 = createTestEvalRun(evalId = evalId, status = EvalRunStatus.QUEUED)
            val evalRun2 = createTestEvalRun(evalId = evalId, status = EvalRunStatus.IN_PROGRESS)
            val evalRun3 = createTestEvalRun(evalId = evalId, status = EvalRunStatus.COMPLETED)
            
            // Save eval runs
            evalRunRepository.createEvalRun(evalRun1)
            evalRunRepository.createEvalRun(evalRun2)
            evalRunRepository.createEvalRun(evalRun3)
            
            // When - filter by COMPLETED status
            val result = evalRunRepository.listEvalRunsByEvalId(evalId, after = null, limit = 10, order = "desc", status = EvalRunStatus.COMPLETED)
            
            // Then
            assertEquals(1, result.size, "Expected 1 result with COMPLETED status")
            assertEquals(evalRun3.id, result[0].id, "Result should be evalRun3 with COMPLETED status")
            assertEquals(EvalRunStatus.COMPLETED, result[0].status, "Result status should be COMPLETED")
        }

    @Test
    fun `listEvalRunsByEvalId with parameters should respect after parameter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            }
            
            // Given - Create 3 runs for the same eval with well-separated timestamps
            val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            val time1 = Instant.now().epochSecond - 1000
            val time2 = Instant.now().epochSecond - 500
            val time3 = Instant.now().epochSecond
            
            val evalRun1 = createTestEvalRun(evalId = evalId, createdAt = time1)
            val evalRun2 = createTestEvalRun(evalId = evalId, createdAt = time2)
            val evalRun3 = createTestEvalRun(evalId = evalId, createdAt = time3)
            
            // Save eval runs and capture the returned (saved) instances
            val savedEvalRun1 = evalRunRepository.createEvalRun(evalRun1)
            val savedEvalRun2 = evalRunRepository.createEvalRun(evalRun2)
            val savedEvalRun3 = evalRunRepository.createEvalRun(evalRun3)
            
            // Print saved IDs and timestamps for debugging
            println("Saved eval run IDs: ${savedEvalRun1.id}, ${savedEvalRun2.id}, ${savedEvalRun3.id}")
            println("Saved eval run timestamps: ${savedEvalRun1.createdAt}, ${savedEvalRun2.createdAt}, ${savedEvalRun3.createdAt}")
            
            // Get all eval runs to verify correct ordering
            val allEvalsDesc = evalRunRepository.listEvalRunsByEvalId(evalId)
            println("All eval runs in descending order: ${allEvalsDesc.map { it.id }}")
            println("All eval runs desc timestamps: ${allEvalsDesc.map { it.createdAt }}")
            
            // Make sure the order is correct before proceeding
            assertEquals(time3, allEvalsDesc[0].createdAt, "First item in DESC should have newest timestamp")
            
            // When - get results after evalRun3 (newest, which is first in DESC order)
            val result = evalRunRepository.listEvalRunsByEvalId(evalId, after = savedEvalRun3.id, limit = 10, order = "desc", status = null)
            
            // Print debug info
            println("Looking for results AFTER eval run with ID ${savedEvalRun3.id} and timestamp ${savedEvalRun3.createdAt}")
            println("Result size: ${result.size}")
            if (result.isNotEmpty()) {
                println("Result IDs: ${result.map { it.id }}")
                println("Result timestamps: ${result.map { it.createdAt }}")
            }
            
            // Then - after the newest in DESC order should be the middle and oldest 
            assertEquals(2, result.size, "Expected 2 results after newest evalRun in DESC order")
            assertEquals(time2, result[0].createdAt, "First result should have middle timestamp")
            assertEquals(time1, result[1].createdAt, "Second result should have oldest timestamp")
            assertEquals(savedEvalRun2.id, result[0].id, "First result should be evalRun2")
            assertEquals(savedEvalRun1.id, result[1].id, "Second result should be evalRun1")
        }

    @Test
    fun `listEvalRunsByEvalId with parameters should respect ascending order parameter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        EvalRun::class.java,
                        MongoEvalRunRepository.EVAL_RUN_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRunRepository.EVAL_RUN_COLLECTION).block()
            }
            
            // Given - Create 3 runs for the same eval
            val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val evalRun1 = createTestEvalRun(evalId = evalId, createdAt = time1)
            val evalRun2 = createTestEvalRun(evalId = evalId, createdAt = time2)
            val evalRun3 = createTestEvalRun(evalId = evalId, createdAt = time3)
            
            // Save eval runs and capture the returned (saved) instances
            val savedEvalRun1 = evalRunRepository.createEvalRun(evalRun1)
            val savedEvalRun2 = evalRunRepository.createEvalRun(evalRun2)
            val savedEvalRun3 = evalRunRepository.createEvalRun(evalRun3)
            
            // Print saved IDs for debugging
            println("Saved eval run IDs: ${savedEvalRun1.id}, ${savedEvalRun2.id}, ${savedEvalRun3.id}")
            
            // When - get results in ascending order
            val result = evalRunRepository.listEvalRunsByEvalId(evalId, after = null, limit = 10, order = "asc", status = null)
            
            // Print result IDs and timestamps for debugging
            println("Result eval run IDs: ${result.map { it.id }}")
            println("Result eval run timestamps: ${result.map { it.createdAt }}")
            
            // Then
            assertEquals(3, result.size, "Expected 3 results")
            
            // Should be sorted by createdAt in ascending order (oldest first)
            assertEquals(savedEvalRun1.id, result[0].id, "First result should be evalRun1 (oldest)")
            assertEquals(savedEvalRun2.id, result[1].id, "Second result should be evalRun2")
            assertEquals(savedEvalRun3.id, result[2].id, "Third result should be evalRun3 (newest)")
        }

    @Test
    fun `updateEvalRun should update an eval run and return the updated eval run`() =
        runTest {
            // Given
            val evalRun = createTestEvalRun(status = EvalRunStatus.QUEUED)
            val savedEvalRun = evalRunRepository.createEvalRun(evalRun)
            
            // When - update status to IN_PROGRESS
            val updatedStatus = EvalRunStatus.IN_PROGRESS
            val evalRunToUpdate = savedEvalRun.copy(status = updatedStatus)
            
            val result = evalRunRepository.updateEvalRun(evalRunToUpdate)
            
            // Then
            assertNotNull(result)
            assertEquals(savedEvalRun.id, result.id)
            assertEquals(updatedStatus, result.status)
            
            // Verify it was updated in the database
            val foundEvalRun = evalRunRepository.getEvalRun(savedEvalRun.id)
            assertNotNull(foundEvalRun)
            assertEquals(updatedStatus, foundEvalRun.status)
        }

    @Test
    fun `updateEvalRun should throw exception for non-existent ID`() =
        runTest {
            // Given
            val nonExistentEvalRun = createTestEvalRun(id = "non-existent-id")
            
            // When/Then
            try {
                evalRunRepository.updateEvalRun(nonExistentEvalRun)
                throw AssertionError("Expected exception was not thrown")
            } catch (e: Exception) {
                // Expected exception
                assertTrue(
                    e.message!!.contains("Evaluation run not found") || 
                        e is IllegalArgumentException,
                )
            }
        }

    @Test
    fun `deleteEvalRun should delete an eval run and return true`() =
        runTest {
            // Given
            val evalRun = createTestEvalRun()
            val savedEvalRun = evalRunRepository.createEvalRun(evalRun)
            
            // When
            val result = evalRunRepository.deleteEvalRun(savedEvalRun.id)
            
            // Then
            assertTrue(result)
            
            // Verify it was deleted
            val deletedEvalRun = evalRunRepository.getEvalRun(savedEvalRun.id)
            assertNull(deletedEvalRun)
        }

    @Test
    fun `deleteEvalRun should return false for non-existent ID`() =
        runTest {
            // When
            val result = evalRunRepository.deleteEvalRun("non-existent-id")
            
            // Then
            assertFalse(result)
        }
} 
