package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.utils.SampleSchemaUtils
import com.openai.core.JsonValue
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.time.Instant
import java.util.*
import kotlin.test.*

class EvalServiceTest {
    private lateinit var evalRepository: EvalRepository
    private lateinit var evalService: EvalService
    private lateinit var headers: MultiValueMap<String, String>

    @BeforeEach
    fun setup() {
        // Create and configure mocks
        evalRepository = mockk()
        headers = LinkedMultiValueMap()
        headers.add("Authorization", "Bearer test-api-key")

        // Mock the SampleSchemaUtils to return a simple schema
        mockkObject(SampleSchemaUtils.Companion)
        every { SampleSchemaUtils.schemaForEvalConfig() } returns
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "model" to mapOf("type" to "string"),
                        "choices" to
                            mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "object"),
                            ),
                    ),
            )

        // Instead of mocking JsonValue.from, we'll use actual implementation but
        // with real objects that can be serialized by Jackson

        // Create the service
        evalService = EvalService(evalRepository)
    }

    @Test
    fun `createEval should create eval with custom data source config`() =
        runTest {
            // Given
            val testingCriterion =
                StringCheckGrader(
                    name = "Test String Check",
                    input = "{{input}}",
                    reference = "expected value",
                    operation = StringCheckGrader.Operation.EQUAL,
                )

            val createRequest =
                CreateEvalRequest(
                    name = "Test Eval",
                    dataSourceConfig =
                        CustomDataSourceConfigRequest(
                            schema =
                                mapOf(
                                    "type" to "object",
                                    "properties" to
                                        mapOf(
                                            "text" to mapOf("type" to "string"),
                                        ),
                                    "required" to listOf("text"),
                                ),
                            includeSampleSchema = true,
                        ),
                    testingCriteria = listOf(testingCriterion),
                    metadata = mapOf("key1" to "value1"),
                )

            // Mock the repository to return a valid eval with ID
            coEvery { evalRepository.createEval(any()) } answers {
                val eval = firstArg<Eval>()
                eval.copy(id = "eval_${UUID.randomUUID().toString().replace("-", "")}")
            }

            // When
            val result = evalService.createEval(createRequest, headers)

            // Then
            assertNotNull(result)
            assertNotNull(result.id)
            assertEquals("Test Eval", result.name)
            assertEquals(mapOf("key1" to "value1"), result.metadata)
            assertTrue(result.dataSourceConfig is CustomDataSourceConfig)
            assertEquals(1, result.testingCriteria.size)

            // Verify that createEval was called with appropriate parameters
            coVerify {
                evalRepository.createEval(
                    match {
                        it.name == "Test Eval" &&
                            it.testingCriteria.size == 1 &&
                            it.testingCriteria[0] is StringCheckGrader &&
                            it.metadata?.get("key1") == "value1"
                    },
                )
            }
        }

    @Test
    fun `createEval should create eval with text similarity grader`() =
        runTest {
            // Given
            val testingCriterion =
                TextSimilarityGrader(
                    name = "Test Text Similarity",
                    input = "{{input}}",
                    reference = "expected text",
                    evaluationMetric = "cosine",
                    passThreshold = 0.8,
                )

            val createRequest =
                CreateEvalRequest(
                    name = "Test Eval",
                    dataSourceConfig =
                        CustomDataSourceConfigRequest(
                            schema =
                                mapOf(
                                    "type" to "object",
                                    "properties" to
                                        mapOf(
                                            "text" to mapOf("type" to "string"),
                                        ),
                                    "required" to listOf("text"),
                                ),
                        ),
                    testingCriteria = listOf(testingCriterion),
                )

            // Mock the repository to return a valid eval with ID
            coEvery { evalRepository.createEval(any()) } answers {
                val eval = firstArg<Eval>()
                eval.copy(id = "eval_${UUID.randomUUID().toString().replace("-", "")}")
            }

            // When
            val result = evalService.createEval(createRequest, headers)

            // Then
            assertNotNull(result)
            assertNotNull(result.id)
            assertEquals("Test Eval", result.name)
            assertTrue(result.dataSourceConfig is CustomDataSourceConfig)
            assertEquals(1, result.testingCriteria.size)
            assertTrue(result.testingCriteria[0] is TextSimilarityGrader)

            // Verify that createEval was called
            coVerify { evalRepository.createEval(any()) }
        }

    @Test
    fun `getEval should return eval by ID`() =
        runTest {
            // Given
            val evalId = "eval_123456"
            val expected = createSampleEval(evalId)

            coEvery { evalRepository.getEval(evalId) } returns expected

            // When
            val result = evalService.getEval(evalId)

            // Then
            assertNotNull(result)
            assertEquals(evalId, result?.id)
            assertEquals("Test Eval", result?.name)

            // Verify repository call
            coVerify { evalRepository.getEval(evalId) }
        }

    @Test
    fun `getEval should return null when eval does not exist`() =
        runTest {
            // Given
            val evalId = "non_existent_eval"

            coEvery { evalRepository.getEval(evalId) } returns null

            // When
            val result = evalService.getEval(evalId)

            // Then
            assertNull(result)

            // Verify repository call
            coVerify { evalRepository.getEval(evalId) }
        }

    @Test
    fun `listEvals should return all evals`() =
        runTest {
            // Given
            val eval1 = createSampleEval("eval_1")
            val eval2 = createSampleEval("eval_2")
            val expected = listOf(eval1, eval2)

            coEvery { evalRepository.listEvals() } returns expected

            // When
            val result = evalService.listEvals()

            // Then
            assertEquals(2, result.size)
            assertEquals("eval_1", result[0].id)
            assertEquals("eval_2", result[1].id)

            // Verify repository call
            coVerify { evalRepository.listEvals() }
        }

    @Test
    fun `listEvals with params should return paginated results`() =
        runTest {
            // Given
            val params =
                ListEvalsParams(
                    limit = 2,
                    order = "desc",
                    after = "eval_0",
                    metadata = mapOf("key1" to "value1"),
                )

            val eval1 = createSampleEval("eval_1")
            val eval2 = createSampleEval("eval_2")
            val evalsFromRepo = listOf(eval1, eval2)

            coEvery { evalRepository.listEvals(params) } returns evalsFromRepo

            // When
            val result = evalService.listEvals(params)

            // Then
            assertEquals(2, result.data.size)
            assertEquals("eval_1", result.data[0].id)
            assertEquals("eval_2", result.data[1].id)
            assertEquals(true, result.hasMore)
            assertEquals("eval_1", result.firstId)
            assertEquals("eval_2", result.lastId)
            assertEquals(2, result.limit)

            // Verify repository call
            coVerify { evalRepository.listEvals(params) }
        }

    @Test
    fun `listEvals with params should handle empty results`() =
        runTest {
            // Given
            val params =
                ListEvalsParams(
                    limit = 10,
                    order = "desc",
                )

            coEvery { evalRepository.listEvals(params) } returns emptyList()

            // When
            val result = evalService.listEvals(params)

            // Then
            assertEquals(0, result.data.size)
            assertEquals(false, result.hasMore)
            assertNull(result.firstId)
            assertNull(result.lastId)
            assertEquals(10, result.limit)

            // Verify repository call
            coVerify { evalRepository.listEvals(params) }
        }

    @Test
    fun `deleteEval should return true when eval exists`() =
        runTest {
            // Given
            val evalId = "eval_123"

            coEvery { evalRepository.deleteEval(evalId) } returns true

            // When
            val result = evalService.deleteEval(evalId)

            // Then
            assertTrue(result)

            // Verify repository call
            coVerify { evalRepository.deleteEval(evalId) }
        }

    @Test
    fun `deleteEval should return false when eval does not exist`() =
        runTest {
            // Given
            val evalId = "non_existent_eval"

            coEvery { evalRepository.deleteEval(evalId) } returns false

            // When
            val result = evalService.deleteEval(evalId)

            // Then
            assertFalse(result)

            // Verify repository call
            coVerify { evalRepository.deleteEval(evalId) }
        }

    @Test
    fun `updateEval should update an eval`() =
        runTest {
            // Given
            val evalId = "eval_123"
            val existingEval = createSampleEval(evalId)
            val updateRequest =
                UpdateEvalRequest(
                    name = "Updated Name",
                    metadata = mapOf("key1" to "updated_value", "key2" to "new_value"),
                )

            coEvery { evalRepository.getEval(evalId) } returns existingEval
            coEvery { evalRepository.updateEval(any()) } answers { firstArg() }

            // When
            val result = evalService.updateEval(evalId, updateRequest)

            // Then
            assertNotNull(result)
            assertEquals(evalId, result?.id)
            assertEquals("Updated Name", result?.name)
            assertEquals("updated_value", result?.metadata?.get("key1"))
            assertEquals("new_value", result?.metadata?.get("key2"))

            // Verify repository calls
            coVerify { evalRepository.getEval(evalId) }
            coVerify { 
                evalRepository.updateEval(
                    match { 
                        it.id == evalId && 
                            it.name == "Updated Name" && 
                            it.metadata?.get("key1") == "updated_value" && 
                            it.metadata?.get("key2") == "new_value"
                    },
                )
            }
        }

    @Test
    fun `updateEval should return null when eval does not exist`() =
        runTest {
            // Given
            val evalId = "non_existent_eval"
            val updateRequest =
                UpdateEvalRequest(
                    name = "Updated Name",
                )

            coEvery { evalRepository.getEval(evalId) } returns null

            // When
            val result = evalService.updateEval(evalId, updateRequest)

            // Then
            assertNull(result)

            // Verify repository call
            coVerify { evalRepository.getEval(evalId) }
            coVerify(exactly = 0) { evalRepository.updateEval(any()) }
        }

    @Test
    fun `updateEval should only update provided fields`() =
        runTest {
            // Given
            val evalId = "eval_123"
            val existingEval = createSampleEval(evalId)
            val updateRequest =
                UpdateEvalRequest(
                    name = "Updated Name",
                    // No metadata update
                )

            coEvery { evalRepository.getEval(evalId) } returns existingEval
            coEvery { evalRepository.updateEval(any()) } answers { firstArg() }

            // When
            val result = evalService.updateEval(evalId, updateRequest)

            // Then
            assertNotNull(result)
            assertEquals(evalId, result?.id)
            assertEquals("Updated Name", result?.name)
            // Metadata should remain unchanged
            assertEquals(existingEval.metadata, result?.metadata)

            // Verify repository calls
            coVerify { evalRepository.getEval(evalId) }
            coVerify { 
                evalRepository.updateEval(
                    match { 
                        it.id == evalId && 
                            it.name == "Updated Name" && 
                            it.metadata == existingEval.metadata
                    },
                )
            }
        }

    @Test
    fun `createEval should throw when CustomDataSourceConfigRequest has invalid schema`() {
        // Setup
        val evalId = "eval_123"
        val headers = LinkedMultiValueMap<String, String>()
        
        // Create an invalid request with missing type
        val invalidSchema =
            mapOf(
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
                "required" to listOf("notifications"),
            )
        
        val createRequest =
            CreateEvalRequest(
                name = "Test Eval",
                dataSourceConfig =
                    CustomDataSourceConfigRequest(
                        schema = invalidSchema,
                        includeSampleSchema = true,
                    ),
                testingCriteria =
                    listOf(
                        StringCheckGrader(
                            name = "Test Criterion",
                            input = "test.item.notifications",
                            reference = "Expected notification",
                            operation = StringCheckGrader.Operation.EQUAL,
                        ),
                    ),
            )
        
        // Assert
        assertThrows<IllegalArgumentException> {
            runBlocking {
                evalService.createEval(createRequest, headers)
            }
        }
    }

    @Test
    fun `createEval should throw when CustomDataSourceConfigRequest has empty properties`() {
        // Setup
        val evalId = "eval_123"
        val headers = LinkedMultiValueMap<String, String>()
        
        // Create an invalid request with empty properties
        val invalidSchema =
            mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf("notifications"),
            )
        
        val createRequest =
            CreateEvalRequest(
                name = "Test Eval",
                dataSourceConfig =
                    CustomDataSourceConfigRequest(
                        schema = invalidSchema,
                        includeSampleSchema = true,
                    ),
                testingCriteria =
                    listOf(
                        StringCheckGrader(
                            name = "Test Criterion",
                            input = "test.item.notifications",
                            reference = "Expected notification",
                            operation = StringCheckGrader.Operation.EQUAL,
                        ),
                    ),
            )
        
        // Assert
        val exception =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    evalService.createEval(createRequest, headers)
                }
            }
        
        assert(exception.message!!.contains("properties must contain at least one property"))
    }

    @Test
    fun `createEval should throw when CustomDataSourceConfigRequest required doesn't match properties`() {
        // Setup
        val evalId = "eval_123"
        val headers = LinkedMultiValueMap<String, String>()
        
        // Create an invalid request where required field doesn't exist in properties
        val invalidSchema =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
                "required" to listOf("nonExistentField"),
            )
        
        val createRequest =
            CreateEvalRequest(
                name = "Test Eval",
                dataSourceConfig =
                    CustomDataSourceConfigRequest(
                        schema = invalidSchema,
                        includeSampleSchema = true,
                    ),
                testingCriteria =
                    listOf(
                        StringCheckGrader(
                            name = "Test Criterion",
                            input = "test.item.notifications",
                            reference = "Expected notification",
                            operation = StringCheckGrader.Operation.EQUAL,
                        ),
                    ),
            )
        
        // Assert
        val exception =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    evalService.createEval(createRequest, headers)
                }
            }
        
        assert(exception.message!!.contains("must match a key in item_schema.properties"))
    }

    // Helper method to create a sample Eval for testing
    private fun createSampleEval(
        id: String = "eval_${UUID.randomUUID().toString().replace("-", "")}",
        name: String = "Test Eval",
        createdAt: Long = Instant.now().epochSecond,
        metadata: Map<String, String>? = mapOf("key1" to "value1", "key2" to "value2"),
    ): Eval {
        val schemaMap =
            mapOf(
                "item" to
                    JsonValue.from(
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "text" to mapOf("type" to "string"),
                                ),
                            "required" to listOf("text"),
                        ),
                    ),
            )
        
        val dataSourceConfig =
            CustomDataSourceConfig(
                schema =
                    mapOf(
                        "type" to JsonValue.from("object"),
                        "properties" to JsonValue.from(schemaMap),
                        "required" to JsonValue.from(listOf("item")),
                    ),
            )
        
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
}
