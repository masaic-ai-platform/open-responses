package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.CustomDataSourceConfig
import ai.masaic.openevals.api.model.Eval
import ai.masaic.openevals.api.model.ListEvalsParams
import ai.masaic.openevals.api.model.StringCheckGrader
import com.openai.core.JsonValue
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
@Import(MongoEvalRepository::class)
@TestPropertySource(properties = ["open-responses.store.type=mongodb"])
@Disabled("Enable this test to run the complete workflow") // Disable this line to run the test
class MongoEvalRepositoryTest {
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

        // Define the collection name for tests, matching the one in MongoEvalRepository
        const val EVAL_COLLECTION = "evals" 
    }

    @Autowired
    private lateinit var evalRepository: MongoEvalRepository

    @Autowired
    private lateinit var mongoTemplate: ReactiveMongoTemplate

    @BeforeEach
    fun setup() {
        // Make sure we start each test with a clean collection
        try {
            mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
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
            mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            
            // Verify the collection is empty after cleanup
            val query =
                org.springframework.data.mongodb.core.query
                    .Query()
            val count = mongoTemplate.count(query, Eval::class.java, MongoEvalRepository.EVAL_COLLECTION).block() ?: 0
            if (count > 0) {
                println("WARNING: Collection still has $count documents after cleanup!")
            }
        }

    // Helper method to create a test Eval object
    private fun createTestEval(
        id: String = "eval_${UUID.randomUUID().toString().replace("-", "")}",
        name: String = "Test Evaluation",
        createdAt: Long = Instant.now().epochSecond,
        metadata: Map<String, String>? = mapOf("key1" to "value1", "key2" to "value2"),
    ): Eval {
        val schemaMap = mapOf<String, JsonValue>()
        val dataSourceConfig = CustomDataSourceConfig(schema = schemaMap)
        
        val testingCriterion =
            StringCheckGrader(
                name = "String Check",
                id = "crit_${UUID.randomUUID().toString().replace("-", "")}",
                input = "{{input}}",
                reference = "expected value",
                operation = StringCheckGrader.Operation.EQUAL,
            )
        
        return Eval(
            id = id,
            name = name,
            createdAt = createdAt,
            dataSourceConfig = dataSourceConfig,
            testingCriteria = listOf(testingCriterion),
            metadata = metadata,
        )
    }

    @Test
    fun `createEval should save an eval with generated ID when ID is blank`() =
        runTest {
            // Given - create eval with blank ID
            val eval = createTestEval(id = "")
            
            // When
            val savedEval = evalRepository.createEval(eval)
            
            // Then
            assertNotNull(savedEval)
            assertTrue(savedEval.id.startsWith("eval_"))
            assertEquals(eval.name, savedEval.name)
            
            // Verify it was saved in the database
            val foundEval = evalRepository.getEval(savedEval.id)
            assertNotNull(foundEval)
            assertEquals(savedEval.id, foundEval.id)
        }

    @Test
    fun `createEval should save an eval with provided ID`() =
        runTest {
            // Given
            val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
            val eval = createTestEval(id = evalId)
            
            // When
            val savedEval = evalRepository.createEval(eval)
            
            // Then
            assertNotNull(savedEval)
            assertEquals(evalId, savedEval.id)
            assertEquals(eval.name, savedEval.name)
            
            // Verify it was saved in the database
            val foundEval = evalRepository.getEval(evalId)
            assertNotNull(foundEval)
            assertEquals(evalId, foundEval.id)
        }

    @Test
    fun `getEval should return null for non-existent ID`() =
        runTest {
            // When
            val result = evalRepository.getEval("non-existent-id")
            
            // Then
            assertNull(result)
        }

    @Test
    fun `getEval should return correct eval for existing ID`() =
        runTest {
            // Given
            val eval = createTestEval()
            evalRepository.createEval(eval)
            
            // When
            val result = evalRepository.getEval(eval.id)
            
            // Then
            assertNotNull(result)
            assertEquals(eval.id, result.id)
            assertEquals(eval.name, result.name)
            assertEquals(eval.metadata, result.metadata)
        }

    @Test
    fun `listEvals should return all evals sorted by createdAt descending`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val eval1 = createTestEval(name = "Test Eval 1", createdAt = time1)
            val eval2 = createTestEval(name = "Test Eval 2", createdAt = time2)
            val eval3 = createTestEval(name = "Test Eval 3", createdAt = time3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // When - get all evals (default is descending order by createdAt)
            val result = evalRepository.listEvals()
            
            // Then
            assertEquals(3, result.size, "Expected 3 results")
            
            // Should be sorted by createdAt in descending order (newest first)
            assertEquals(savedEval3.id, result[0].id, "First result should be eval3 (newest)")
            assertEquals(savedEval2.id, result[1].id, "Second result should be eval2")
            assertEquals(savedEval1.id, result[2].id, "Third result should be eval1 (oldest)")
        }

    @Test
    fun `listEvals with params should respect pagination parameters`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val eval1 = createTestEval(name = "Test Eval 1", createdAt = time1)
            val eval2 = createTestEval(name = "Test Eval 2", createdAt = time2)
            val eval3 = createTestEval(name = "Test Eval 3", createdAt = time3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // When - get results after eval3 (newest, which is first in DESC order)
            val params = ListEvalsParams(limit = 10, after = savedEval3.id)
            val result = evalRepository.listEvals(params)
            
            // Then
            assertEquals(2, result.size, "Expected 2 results after eval3")
            assertEquals(savedEval2.id, result[0].id, "First result should be eval2")
            assertEquals(savedEval1.id, result[1].id, "Second result should be eval1")
        }

    @Test
    fun `listEvals with params should respect limit parameter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val eval1 = createTestEval(name = "Test Eval 1", createdAt = time1)
            val eval2 = createTestEval(name = "Test Eval 2", createdAt = time2)
            val eval3 = createTestEval(name = "Test Eval 3", createdAt = time3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // When - limit to 2 results (in descending order by default)
            val params = ListEvalsParams(limit = 2)
            val result = evalRepository.listEvals(params)
            
            // Then
            assertEquals(2, result.size, "Expected 2 results due to limit")
            assertEquals(savedEval3.id, result[0].id, "First result should be eval3 (newest)")
            assertEquals(savedEval2.id, result[1].id, "Second result should be eval2")
        }

    @Test
    fun `listEvals with params should filter by metadata`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - ensure distinct metadata
            val metadata1 = mapOf("key1" to "value1", "key2" to "value2")
            val metadata2 = mapOf("key1" to "value1", "key3" to "value3")
            val metadata3 = mapOf("key1" to "different", "key2" to "value2")
            
            val eval1 = createTestEval(name = "Test Eval 1", metadata = metadata1)
            val eval2 = createTestEval(name = "Test Eval 2", metadata = metadata2)
            val eval3 = createTestEval(name = "Test Eval 3", metadata = metadata3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            println("Metadata1: $metadata1")
            println("Metadata2: $metadata2")
            println("Metadata3: $metadata3")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // When - filter by metadata
            val params = ListEvalsParams(metadata = mapOf("key1" to "value1"))
            val result = evalRepository.listEvals(params)
            
            // Print result info
            println("Result size: ${result.size}")
            println("Result IDs: ${result.map { it.id }}")
            println("Result metadata: ${result.map { it.metadata }}")
            
            // Then
            assertEquals(2, result.size, "Expected 2 results matching the metadata filter")
            
            // Results should contain eval1 and eval2, which have key1=value1
            val resultIds = result.map { it.id }
            assertTrue(resultIds.contains(savedEval1.id), "Results should contain eval1")
            assertTrue(resultIds.contains(savedEval2.id), "Results should contain eval2")
            assertFalse(resultIds.contains(savedEval3.id), "Results should not contain eval3")
        }

    @Test
    fun `listEvals with params should respect before parameter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps with more separation
            val time1 = Instant.now().epochSecond - 1000
            val time2 = Instant.now().epochSecond - 500
            val time3 = Instant.now().epochSecond
            
            // Create evals with very specific timestamps
            val eval1 = createTestEval(name = "Test Eval 1", createdAt = time1)
            val eval2 = createTestEval(name = "Test Eval 2", createdAt = time2)
            val eval3 = createTestEval(name = "Test Eval 3", createdAt = time3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs and timestamps for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            println("Saved eval timestamps: ${savedEval1.createdAt}, ${savedEval2.createdAt}, ${savedEval3.createdAt}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // Get all evals in different orders to verify
            val allEvalsDesc = evalRepository.listEvals(ListEvalsParams(order = "desc"))
            val allEvalsAsc = evalRepository.listEvals(ListEvalsParams(order = "asc"))
            
            println("All evals in descending order: ${allEvalsDesc.map { it.id }}")
            println("All evals in ascending order: ${allEvalsAsc.map { it.id }}")
            println("All evals desc timestamps: ${allEvalsDesc.map { it.createdAt }}")
            println("All evals asc timestamps: ${allEvalsAsc.map { it.createdAt }}")
            
            // Make sure the order is correct before proceeding
            assertEquals(time3, allEvalsDesc[0].createdAt, "First item in DESC should have newest timestamp")
            assertEquals(time1, allEvalsAsc[0].createdAt, "First item in ASC should have oldest timestamp")
            
            // Instead of using the saved ID (which may have issues), let's re-get the specific eval
            val middleEval = allEvalsDesc[1] // This should be eval2 (middle timestamp)
            
            // Confirm this is actually the middle timestamp eval
            assertEquals(time2, middleEval.createdAt, "Selected eval should have middle timestamp")
            
            // When - get results before middleEval
            val params = ListEvalsParams(before = middleEval.id, order = "desc")
            val result = evalRepository.listEvals(params)
            
            // Print extensive debug info
            println("Looking for results BEFORE eval with ID ${middleEval.id} and timestamp ${middleEval.createdAt}")
            println("Result size: ${result.size}")
            if (result.isNotEmpty()) {
                println("Result IDs: ${result.map { it.id }}")
                println("Result timestamps: ${result.map { it.createdAt }}")
            }
            
            // Then - in DESC order, before the middle item should be the newest item
            assertEquals(1, result.size, "Expected 1 result before middle eval in DESC order")
            
            // Assert based on timestamp (more reliable than ID)
            assertEquals(time3, result[0].createdAt, "Expected newest eval's timestamp")
            assertEquals(allEvalsDesc[0].id, result[0].id, "Expected newest eval's ID")
        }

    @Test
    fun `listEvals with params should respect ascending order parameter`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps
            val time1 = Instant.now().epochSecond - 100
            val time2 = Instant.now().epochSecond - 50
            val time3 = Instant.now().epochSecond
            
            val eval1 = createTestEval(name = "Test Eval 1", createdAt = time1)
            val eval2 = createTestEval(name = "Test Eval 2", createdAt = time2)
            val eval3 = createTestEval(name = "Test Eval 3", createdAt = time3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            println("Saved eval timestamps: ${savedEval1.createdAt}, ${savedEval2.createdAt}, ${savedEval3.createdAt}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // When - get results in ascending order
            val params = ListEvalsParams(order = "asc")
            val result = evalRepository.listEvals(params)
            
            // Print result IDs and timestamps for debugging
            println("Result eval IDs: ${result.map { it.id }}")
            println("Result eval timestamps: ${result.map { it.createdAt }}")
            
            // Then
            assertEquals(3, result.size, "Expected 3 results")
            
            // Should be sorted by createdAt in ascending order (oldest first)
            assertEquals(savedEval1.id, result[0].id, "First result should be eval1 (oldest)")
            assertEquals(savedEval2.id, result[1].id, "Second result should be eval2")
            assertEquals(savedEval3.id, result[2].id, "Third result should be eval3 (newest)")
        }

    @Test
    fun `deleteEval should delete an eval and return true`() =
        runTest {
            // Given
            val eval = createTestEval()
            evalRepository.createEval(eval)
            
            // When
            val result = evalRepository.deleteEval(eval.id)
            
            // Then
            assertTrue(result)
            
            // Verify it was deleted
            val deletedEval = evalRepository.getEval(eval.id)
            assertNull(deletedEval)
        }

    @Test
    fun `deleteEval should return false for non-existent ID`() =
        runTest {
            // When
            val result = evalRepository.deleteEval("non-existent-id")
            
            // Then
            assertFalse(result)
        }

    @Test
    fun `updateEval should update an eval and return the updated eval`() =
        runTest {
            // Given
            val eval = createTestEval()
            evalRepository.createEval(eval)
            
            // When - update the eval with new metadata
            val updatedMetadata = mapOf("key1" to "updated-value", "key3" to "new-value")
            val updatedName = "Updated Test Eval"
            val evalToUpdate = eval.copy(name = updatedName, metadata = updatedMetadata)
            
            val result = evalRepository.updateEval(evalToUpdate)
            
            // Then
            assertNotNull(result)
            assertEquals(eval.id, result.id)
            assertEquals(updatedName, result.name)
            assertEquals(updatedMetadata, result.metadata)
            
            // Verify it was updated in the database
            val foundEval = evalRepository.getEval(eval.id)
            assertNotNull(foundEval)
            assertEquals(updatedName, foundEval.name)
            assertEquals(updatedMetadata, foundEval.metadata)
        }

    @Test
    fun `updateEval should return null for non-existent ID`() =
        runTest {
            // Given
            val nonExistentEval = createTestEval(id = "non-existent-id")
            
            // When
            val result = evalRepository.updateEval(nonExistentEval)
            
            // Then
            assertNull(result)
        }

    @Test
    fun `listEvals with params should respect before parameter in ascending order`() =
        runTest {
            // Ensure we start with a clean collection
            val initialCount =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            if (initialCount > 0) {
                mongoTemplate.dropCollection(MongoEvalRepository.EVAL_COLLECTION).block()
            }
            
            // Given - We need to ensure we know the exact ordering by using specific timestamps with more separation
            val time1 = Instant.now().epochSecond - 1000
            val time2 = Instant.now().epochSecond - 500
            val time3 = Instant.now().epochSecond
            
            // Create evals with very specific timestamps
            val eval1 = createTestEval(name = "Test Eval 1", createdAt = time1)
            val eval2 = createTestEval(name = "Test Eval 2", createdAt = time2)
            val eval3 = createTestEval(name = "Test Eval 3", createdAt = time3)
            
            // Save evals and capture the returned (saved) instances
            val savedEval1 = evalRepository.createEval(eval1)
            val savedEval2 = evalRepository.createEval(eval2)
            val savedEval3 = evalRepository.createEval(eval3)
            
            // Print saved IDs and timestamps for debugging
            println("Saved eval IDs: ${savedEval1.id}, ${savedEval2.id}, ${savedEval3.id}")
            println("Saved eval timestamps: ${savedEval1.createdAt}, ${savedEval2.createdAt}, ${savedEval3.createdAt}")
            
            // Verify how many docs we have before testing
            val count =
                mongoTemplate
                    .count(
                        org.springframework.data.mongodb.core.query
                            .Query(), 
                        Eval::class.java,
                        MongoEvalRepository.EVAL_COLLECTION,
                    ).block() ?: 0
            assertEquals(3, count, "Expected 3 documents in database before running test")
            
            // Get all evals in ascending order to find our target by timestamp
            val allEvalsAsc = evalRepository.listEvals(ListEvalsParams(order = "asc"))
            
            println("All evals in ascending order: ${allEvalsAsc.map { it.id }}")
            println("All evals asc timestamps: ${allEvalsAsc.map { it.createdAt }}")
            
            // Make sure the order is correct before proceeding
            assertEquals(time1, allEvalsAsc[0].createdAt, "First item in ASC should have oldest timestamp")
            assertEquals(time2, allEvalsAsc[1].createdAt, "Second item in ASC should have middle timestamp")
            assertEquals(time3, allEvalsAsc[2].createdAt, "Third item in ASC should have newest timestamp")
            
            // Get the middle eval by timestamp
            val middleEval = allEvalsAsc[1]
            
            // When - get results before middleEval in ASC order
            val params = ListEvalsParams(before = middleEval.id, order = "asc")
            val result = evalRepository.listEvals(params)
            
            // Print debug info
            println("Looking for results BEFORE eval with ID ${middleEval.id} and timestamp ${middleEval.createdAt} in ASC order")
            println("Result size: ${result.size}")
            if (result.isNotEmpty()) {
                println("Result IDs: ${result.map { it.id }}")
                println("Result timestamps: ${result.map { it.createdAt }}")
            }
            
            // Then - in ASC order, before the middle item should be the oldest item
            assertEquals(1, result.size, "Expected 1 result before middle eval in ASC order")
            assertEquals(time1, result[0].createdAt, "Expected oldest eval's timestamp")
            assertEquals(allEvalsAsc[0].id, result[0].id, "Expected oldest eval's ID")
        }
} 
