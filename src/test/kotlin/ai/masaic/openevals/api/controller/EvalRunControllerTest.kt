package ai.masaic.openevals.api.controller

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.service.EvalRunService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EvalRunControllerTest {
    private lateinit var evalRunService: EvalRunService
    private lateinit var evalRunController: EvalRunController
    private lateinit var headers: MultiValueMap<String, String>

    @BeforeEach
    fun setup() {
        evalRunService = mockk()
        evalRunController = EvalRunController(evalRunService)
        headers =
            LinkedMultiValueMap<String, String>().apply {
                add("Authorization", "Bearer test-api-key")
            }
    }

    @Test
    fun `createEvalRun should return created evaluation run with status 201`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val dataSource: RunDataSource = mockk<CompletionsRunDataSource>()
            val request =
                CreateEvalRunRequest(
                    name = "Test Eval Run",
                    dataSource = dataSource,
                    metadata = mapOf("key" to "value"),
                )

            val expectedEvalRun =
                EvalRun(
                    apiKey = "test-api-key",
                    id = "run-123",
                    evalId = evalId,
                    name = request.name,
                    dataSource = dataSource,
                    model = "gpt-4",
                    metadata = request.metadata,
                )

            coEvery { evalRunService.createEvalRun(headers, evalId, request) } returns expectedEvalRun

            // Act
            val response = evalRunController.createEvalRun(headers, evalId, request)

            // Assert
            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(expectedEvalRun, response.body)
            coVerify(exactly = 1) { evalRunService.createEvalRun(headers, evalId, request) }
        }

    @Test
    fun `getEvalRun should return evaluation run when found`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "run-123"
            val expectedEvalRun =
                EvalRun(
                    apiKey = "test-api-key",
                    id = runId,
                    evalId = evalId,
                    name = "Test Eval Run",
                    dataSource = mockk(),
                    model = "gpt-4",
                )

            coEvery { evalRunService.getEvalRun(runId) } returns expectedEvalRun

            // Act
            val response = evalRunController.getEvalRun(evalId, runId)

            // Assert
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(expectedEvalRun, response.body)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
        }

    @Test
    fun `getEvalRun should throw ResponseStatusException when not found`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "non-existent-id"
            coEvery { evalRunService.getEvalRun(runId) } returns null

            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.getEvalRun(evalId, runId)
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation run not found with ID: $runId", exception.reason)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
        }

    @Test
    fun `getEvalRun should throw ResponseStatusException when run does not belong to eval`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "run-123"
            val wrongEvalId = "eval-456"
        
            val evalRun =
                EvalRun(
                    apiKey = "test-api-key",
                    id = runId,
                    evalId = wrongEvalId, // Different eval ID
                    name = "Test Eval Run",
                    dataSource = mockk(),
                    model = "gpt-4",
                )
        
            coEvery { evalRunService.getEvalRun(runId) } returns evalRun

            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.getEvalRun(evalId, runId)
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation run with ID $runId does not belong to evaluation with ID $evalId", exception.reason)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
        }

    @Test
    fun `listEvalRuns should return list of evaluation runs`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val evalRuns =
                listOf(
                    EvalRun(
                        apiKey = "test-api-key",
                        id = "run-1",
                        evalId = evalId,
                        name = "Run 1",
                        dataSource = mockk(),
                        model = "gpt-4",
                    ),
                    EvalRun(
                        apiKey = "test-api-key",
                        id = "run-2",
                        evalId = evalId,
                        name = "Run 2",
                        dataSource = mockk(),
                        model = "gpt-4",
                    ),
                )

            coEvery {
                evalRunService.listEvalRunsByEvalId(
                    evalId = evalId,
                    after = null,
                    limit = 20,
                    order = "asc",
                    status = null,
                )
            } returns evalRuns

            // Act
            val response =
                evalRunController.listEvalRuns(
                    evalId = evalId,
                    after = null,
                    limit = 20,
                    order = "asc",
                    status = null,
                )

            // Assert
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(evalRuns, response.body)
            coVerify(exactly = 1) {
                evalRunService.listEvalRunsByEvalId(
                    evalId = evalId,
                    after = null,
                    limit = 20,
                    order = "asc",
                    status = null,
                )
            }
        }

    @Test
    fun `listEvalRuns should filter runs by status`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val status = "completed"
            val statusEnum = EvalRunStatus.COMPLETED
        
            val evalRuns =
                listOf(
                    EvalRun(
                        apiKey = "test-api-key",
                        id = "run-1",
                        evalId = evalId,
                        name = "Run 1",
                        dataSource = mockk(),
                        model = "gpt-4",
                        status = EvalRunStatus.COMPLETED,
                    ),
                )

            coEvery {
                evalRunService.listEvalRunsByEvalId(
                    evalId = evalId,
                    after = null,
                    limit = 20,
                    order = "asc",
                    status = statusEnum,
                )
            } returns evalRuns

            // Act
            val response =
                evalRunController.listEvalRuns(
                    evalId = evalId,
                    after = null,
                    limit = 20,
                    order = "asc",
                    status = status,
                )

            // Assert
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(evalRuns, response.body)
            coVerify(exactly = 1) {
                evalRunService.listEvalRunsByEvalId(
                    evalId = evalId,
                    after = null,
                    limit = 20,
                    order = "asc",
                    status = statusEnum,
                )
            }
        }

    @Test
    fun `listEvalRuns should throw exception for invalid order`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
        
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.listEvalRuns(
                        evalId = evalId,
                        after = null,
                        limit = 20,
                        order = "invalid",
                        status = null,
                    )
                }
        
            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            assertEquals("Order must be either 'asc' or 'desc'", exception.reason)
        }

    @Test
    fun `listEvalRuns should throw exception for invalid status`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
        
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.listEvalRuns(
                        evalId = evalId,
                        after = null,
                        limit = 20,
                        order = "asc",
                        status = "invalid_status",
                    )
                }
        
            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            assertEquals("Status must be one of: 'queued', 'in_progress', 'failed', 'completed', 'canceled'", exception.reason)
        }

    @Test
    fun `deleteEvalRun should return no content when successful`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "run-123"
        
            val evalRun =
                EvalRun(
                    apiKey = "test-api-key",
                    id = runId,
                    evalId = evalId,
                    name = "Test Eval Run",
                    dataSource = mockk(),
                    model = "gpt-4",
                )
        
            coEvery { evalRunService.getEvalRun(runId) } returns evalRun
            coEvery { evalRunService.deleteEvalRun(runId) } returns true

            // Act
            val response = evalRunController.deleteEvalRun(evalId, runId)

            // Assert
            assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
            assertNotNull(response)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
            coVerify(exactly = 1) { evalRunService.deleteEvalRun(runId) }
        }

    @Test
    fun `deleteEvalRun should throw ResponseStatusException when run not found`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "non-existent-id"
        
            coEvery { evalRunService.getEvalRun(runId) } returns null

            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.deleteEvalRun(evalId, runId)
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation run not found with ID: $runId", exception.reason)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
            coVerify(exactly = 0) { evalRunService.deleteEvalRun(runId) }
        }

    @Test
    fun `deleteEvalRun should throw ResponseStatusException when run does not belong to eval`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "run-123"
            val wrongEvalId = "eval-456"
        
            val evalRun =
                EvalRun(
                    apiKey = "test-api-key",
                    id = runId,
                    evalId = wrongEvalId, // Different eval ID
                    name = "Test Eval Run",
                    dataSource = mockk(),
                    model = "gpt-4",
                )
        
            coEvery { evalRunService.getEvalRun(runId) } returns evalRun

            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.deleteEvalRun(evalId, runId)
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation run with ID $runId does not belong to evaluation with ID $evalId", exception.reason)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
            coVerify(exactly = 0) { evalRunService.deleteEvalRun(runId) }
        }

    @Test
    fun `deleteEvalRun should throw ResponseStatusException when deletion fails`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val runId = "run-123"
        
            val evalRun =
                EvalRun(
                    apiKey = "test-api-key",
                    id = runId,
                    evalId = evalId,
                    name = "Test Eval Run",
                    dataSource = mockk(),
                    model = "gpt-4",
                )
        
            coEvery { evalRunService.getEvalRun(runId) } returns evalRun
            coEvery { evalRunService.deleteEvalRun(runId) } returns false

            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalRunController.deleteEvalRun(evalId, runId)
                }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation run not found with ID: $runId", exception.reason)
            coVerify(exactly = 1) { evalRunService.getEvalRun(runId) }
            coVerify(exactly = 1) { evalRunService.deleteEvalRun(runId) }
        }
} 
