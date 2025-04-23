package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.repository.EvalRunRepository
import ai.masaic.openevals.api.service.runner.EvalRunner
import ai.masaic.openevals.api.validation.EvalRunValidator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import kotlin.test.*

class EvalRunServiceTest {
    private lateinit var evalRunRepository: EvalRunRepository
    private lateinit var evalRepository: EvalRepository 
    private lateinit var evalRunner: EvalRunner
    private lateinit var evalRunValidator: EvalRunValidator
    private lateinit var evalRunService: EvalRunService
    private lateinit var headers: MultiValueMap<String, String>

    @BeforeEach
    fun setup() {
        // Create and configure mocks
        evalRunRepository = mockk()
        evalRepository = mockk()
        evalRunner = mockk()
        evalRunValidator = mockk()
        
        // Create the service
        evalRunService = EvalRunService(evalRunRepository, evalRepository, evalRunner, evalRunValidator)
        
        // Setup headers with API key
        headers = LinkedMultiValueMap()
        headers.add("Authorization", "Bearer test-api-key")
    }

    @Test
    fun `createEvalRun should create run with completions data source`() =
        runTest {
            // Given
            val evalId = "eval_123456"
            val eval = createSampleEval(evalId)
            val dataSource =
                CompletionsRunDataSource(
                    inputMessages =
                        TemplateInputMessages(
                            template = listOf(ChatMessage("user", "Hello")),
                        ),
                    model = "gpt-4",
                    source = FileDataSource(id = "file_123"),
                )
            val request =
                CreateEvalRunRequest(
                    name = "Test Run",
                    dataSource = dataSource,
                    metadata = mapOf("key1" to "value1"),
                )
        
            // Mock repository responses
            coEvery { evalRepository.getEval(evalId) } returns eval
            coEvery { evalRunRepository.createEvalRun(any()) } answers {
                val evalRun = firstArg<EvalRun>()
                evalRun.copy(id = "run_abcdef123456")
            }
            coJustRun { evalRunner.processEvalRun(any()) }
            coJustRun { evalRunValidator.validate(request) }
        
            // When
            val result = evalRunService.createEvalRun(headers, evalId, request)
        
            // Then
            assertNotNull(result)
            assertEquals("run_abcdef123456", result.id)
            assertEquals("Test Run", result.name)
            assertEquals(evalId, result.evalId)
            assertEquals(EvalRunStatus.QUEUED, result.status)
            assertTrue(result.dataSource is CompletionsRunDataSource)
            assertEquals("gpt-4", result.model)
            assertEquals(mapOf("key1" to "value1"), result.metadata)
        
            // Verify repository calls
            coVerify { 
                evalRepository.getEval(evalId)
                evalRunValidator.validate(request)
                evalRunRepository.createEvalRun(
                    match {
                        it.name == "Test Run" &&
                            it.evalId == evalId &&
                            it.apiKey == "test-api-key" &&
                            it.status == EvalRunStatus.QUEUED
                    },
                )
            }
            coVerify(exactly = 1) { evalRunner.processEvalRun(any()) }
        }

    @Test
    fun `createEvalRun should throw exception when eval does not exist`() =
        runTest {
            // Given
            val evalId = "non_existent_eval"
            val dataSource =
                mockk<CompletionsRunDataSource> {
                    every { model } returns "gpt-4"
                }
            val request =
                CreateEvalRunRequest(
                    name = "Test Run",
                    dataSource = dataSource,
                    metadata = mapOf("key1" to "value1"),
                )
        
            // Mock repository response
            coEvery { evalRepository.getEval(evalId) } returns null
        
            // When/Then
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunService.createEvalRun(headers, evalId, request)
                }
        
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertTrue(exception.reason?.contains("not found") == true)
        
            // Verify repository call
            coVerify { evalRepository.getEval(evalId) }
            coVerify(exactly = 0) { evalRunValidator.validate(any()) }
            coVerify(exactly = 0) { evalRunRepository.createEvalRun(any()) }
            coVerify(exactly = 0) { evalRunner.processEvalRun(any()) }
        }

    @Test
    fun `createEvalRun should throw exception when validation fails`() =
        runTest {
            // Given
            val evalId = "eval_123456"
            val eval = createSampleEval(evalId)
            val dataSource =
                CompletionsRunDataSource(
                    inputMessages =
                        TemplateInputMessages(
                            template = listOf(ChatMessage("user", "{{ item.invalid_field }}")),
                        ),
                    model = "gpt-4",
                    source = FileDataSource(id = "file_123"),
                )
            val request =
                CreateEvalRunRequest(
                    name = "Test Run",
                    dataSource = dataSource,
                    metadata = mapOf("key1" to "value1"),
                )
        
            // Mock responses
            coEvery { evalRepository.getEval(evalId) } returns eval
            coEvery { evalRunValidator.validate(request) } throws 
                IllegalArgumentException("File source data content is not matching with the template used in input_messages")
        
            // When/Then
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunService.createEvalRun(headers, evalId, request)
                }
        
            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            assertTrue(exception.reason?.contains("not matching") == true)
        
            // Verify calls
            coVerify { evalRepository.getEval(evalId) }
            coVerify { evalRunValidator.validate(request) }
            coVerify(exactly = 0) { evalRunRepository.createEvalRun(any()) }
            coVerify(exactly = 0) { evalRunner.processEvalRun(any()) }
        }

    @Test
    fun `createEvalRun should throw exception when API key is missing`() =
        runTest {
            // Given
            val evalId = "eval_123456"
            val dataSource =
                mockk<CompletionsRunDataSource> {
                    every { model } returns "gpt-4"
                }
            val request =
                CreateEvalRunRequest(
                    name = "Test Run",
                    dataSource = dataSource,
                    metadata = mapOf("key1" to "value1"),
                )
            val emptyHeaders = LinkedMultiValueMap<String, String>()
        
            // The method first checks if the eval exists, so we need to mock this
            // to allow the code to progress to the point where it extracts the API key
            coEvery { evalRepository.getEval(evalId) } returns createSampleEval(evalId)
            coJustRun { evalRunValidator.validate(request) }
        
            // When/Then
            val exception =
                assertThrows<IllegalStateException> {
                    evalRunService.createEvalRun(emptyHeaders, evalId, request)
                }
        
            assertTrue(exception.message?.contains("api-key is missing") == true)
        
            // Verify repository calls - we should reach getEval but not createEvalRun
            coVerify(exactly = 1) { evalRepository.getEval(evalId) }
            coVerify(exactly = 1) { evalRunValidator.validate(request) }
            coVerify(exactly = 0) { evalRunRepository.createEvalRun(any()) }
        }

    @Test
    fun `getEvalRun should return evaluation run by ID`() =
        runTest {
            // Given
            val evalRunId = "run_123456"
            val expectedRun = createSampleEvalRun(evalRunId)
        
            // Mock repository response
            coEvery { evalRunRepository.getEvalRun(evalRunId) } returns expectedRun
        
            // When
            val result = evalRunService.getEvalRun(evalRunId)
        
            // Then
            assertNotNull(result)
            assertEquals(evalRunId, result?.id)
        
            // Verify repository call
            coVerify { evalRunRepository.getEvalRun(evalRunId) }
        }

    @Test
    fun `getEvalRun should return null when run does not exist`() =
        runTest {
            // Given
            val evalRunId = "non_existent_run"
        
            // Mock repository response
            coEvery { evalRunRepository.getEvalRun(evalRunId) } returns null
        
            // When
            val result = evalRunService.getEvalRun(evalRunId)
        
            // Then
            assertNull(result)
        
            // Verify repository call
            coVerify { evalRunRepository.getEvalRun(evalRunId) }
        }

    @Test
    fun `listEvalRunsByEvalId should return all runs for an eval`() =
        runTest {
            // Given
            val evalId = "eval_123456"
            val eval = createSampleEval(evalId)
            val runs =
                listOf(
                    createSampleEvalRun("run_1", evalId),
                    createSampleEvalRun("run_2", evalId),
                )
        
            // Mock repository responses
            coEvery { evalRepository.getEval(evalId) } returns eval
            coEvery { evalRunRepository.listEvalRunsByEvalId(evalId) } returns runs
        
            // When
            val result = evalRunService.listEvalRunsByEvalId(evalId)
        
            // Then
            assertEquals(2, result.size)
            assertEquals("run_1", result[0].id)
            assertEquals("run_2", result[1].id)
        
            // Verify repository calls
            coVerify { 
                evalRepository.getEval(evalId)
                evalRunRepository.listEvalRunsByEvalId(evalId)
            }
        }

    @Test
    fun `listEvalRunsByEvalId should throw exception when eval does not exist`() =
        runTest {
            // Given
            val evalId = "non_existent_eval"
        
            // Mock repository response
            coEvery { evalRepository.getEval(evalId) } returns null
        
            // When/Then
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunService.listEvalRunsByEvalId(evalId)
                }
        
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertTrue(exception.reason?.contains("not found") == true)
        
            // Verify repository call
            coVerify { evalRepository.getEval(evalId) }
            coVerify(exactly = 0) { evalRunRepository.listEvalRunsByEvalId(evalId) }
        }

    @Test
    fun `listEvalRunsByEvalId with params should return filtered runs`() =
        runTest {
            // Given
            val evalId = "eval_123456"
            val eval = createSampleEval(evalId)
            val runs =
                listOf(
                    createSampleEvalRun("run_1", evalId),
                    createSampleEvalRun("run_2", evalId),
                )
            val after = "run_0"
            val limit = 10
            val order = "desc"
            val status = EvalRunStatus.COMPLETED
        
            // Mock repository responses
            coEvery { evalRepository.getEval(evalId) } returns eval
            coEvery { 
                evalRunRepository.listEvalRunsByEvalId(
                    evalId,
                    after,
                    limit,
                    order,
                    status,
                ) 
            } returns runs
        
            // When
            val result = evalRunService.listEvalRunsByEvalId(evalId, after, limit, order, status)
        
            // Then
            assertEquals(2, result.size)
            assertEquals("run_1", result[0].id)
            assertEquals("run_2", result[1].id)
        
            // Verify repository calls
            coVerify { 
                evalRepository.getEval(evalId)
                evalRunRepository.listEvalRunsByEvalId(evalId, after, limit, order, status)
            }
        }

    @Test
    fun `listEvalRunsByEvalId with params should throw exception when eval does not exist`() =
        runTest {
            // Given
            val evalId = "non_existent_eval"
            val after = "run_0"
            val limit = 10
            val order = "desc"
            val status = EvalRunStatus.COMPLETED
        
            // Mock repository response
            coEvery { evalRepository.getEval(evalId) } returns null
        
            // When/Then
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunService.listEvalRunsByEvalId(evalId, after, limit, order, status)
                }
        
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertTrue(exception.reason?.contains("not found") == true)
        
            // Verify repository call
            coVerify { evalRepository.getEval(evalId) }
            coVerify(exactly = 0) { 
                evalRunRepository.listEvalRunsByEvalId(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `deleteEvalRun should return true when run exists`() =
        runTest {
            // Given
            val evalRunId = "run_123456"
        
            // Mock repository response
            coEvery { evalRunRepository.deleteEvalRun(evalRunId) } returns true
        
            // When
            val result = evalRunService.deleteEvalRun(evalRunId)
        
            // Then
            assertTrue(result)
        
            // Verify repository call
            coVerify { evalRunRepository.deleteEvalRun(evalRunId) }
        }

    @Test
    fun `deleteEvalRun should return false when run does not exist`() =
        runTest {
            // Given
            val evalRunId = "non_existent_run"
        
            // Mock repository response
            coEvery { evalRunRepository.deleteEvalRun(evalRunId) } returns false
        
            // When
            val result = evalRunService.deleteEvalRun(evalRunId)
        
            // Then
            assertFalse(result)
        
            // Verify repository call
            coVerify { evalRunRepository.deleteEvalRun(evalRunId) }
        }

    // Helper method to create a sample eval run for testing
    private fun createSampleEvalRun(
        id: String = "run_123456",
        evalId: String = "eval_123456",
        name: String = "Test Run",
        status: EvalRunStatus = EvalRunStatus.COMPLETED,
    ): EvalRun {
        val dataSource =
            CompletionsRunDataSource(
                inputMessages =
                    TemplateInputMessages(
                        template = listOf(ChatMessage("user", "Hello")),
                    ),
                model = "gpt-4",
                source = FileDataSource(id = "file_123"),
            )
        
        return EvalRun(
            apiKey = "test-api-key",
            id = id,
            evalId = evalId,
            name = name,
            createdAt = Instant.now().epochSecond,
            dataSource = dataSource,
            model = "gpt-4",
            status = status,
            metadata = mapOf("key1" to "value1"),
        )
    }

    // Helper method to create a sample eval for testing
    private fun createSampleEval(
        id: String = "eval_123456",
        name: String = "Test Eval",
    ): Eval {
        val testingCriterion =
            StringCheckGrader(
                id = "crit_12345",
                name = "String Check",
                input = "{{input}}",
                reference = "expected value",
                operation = StringCheckGrader.Operation.EQUAL,
            )
        
        return Eval(
            id = id,
            name = name,
            createdAt = Instant.now().epochSecond,
            dataSourceConfig =
                CustomDataSourceConfig(
                    schema = emptyMap(),
                ),
            testingCriteria = listOf(testingCriterion),
            metadata = mapOf("key1" to "value1"),
        )
    }
} 
