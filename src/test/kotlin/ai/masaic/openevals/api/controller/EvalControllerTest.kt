package ai.masaic.openevals.api.controller

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.service.EvalService
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

class EvalControllerTest {
    private lateinit var evalService: EvalService
    private lateinit var evalController: EvalController
    private lateinit var headers: MultiValueMap<String, String>

    @BeforeEach
    fun setup() {
        evalService = mockk()
        evalController = EvalController(evalService)
        headers =
            LinkedMultiValueMap<String, String>().apply {
                add("Authorization", "Bearer test-api-key")
            }
    }

    @Test
    fun `createEval should return created evaluation with status 201`() =
        runBlocking {
            // Arrange
            val request =
                CreateEvalRequest(
                    name = "Test Eval",
                    dataSourceConfig = mockk<CustomDataSourceConfig>(),
                    testingCriteria = listOf<TestingCriterion>(mockk()),
                    metadata = mapOf("key" to "value"),
                )
        
            val expectedEval =
                Eval(
                    id = "eval-123",
                    name = request.name,
                    dataSourceConfig = request.dataSourceConfig,
                    testingCriteria = request.testingCriteria,
                    metadata = request.metadata,
                )
        
            coEvery { evalService.createEval(request, headers) } returns expectedEval
        
            // Act
            val response = evalController.createEval(request, headers)
        
            // Assert
            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals(expectedEval, response.body)
            coVerify(exactly = 1) { evalService.createEval(request, headers) }
        }

    @Test
    fun `getEval should return evaluation when found`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val expectedEval =
                Eval(
                    id = evalId,
                    name = "Test Eval",
                    dataSourceConfig = mockk(),
                    testingCriteria = listOf(mockk()),
                )
        
            coEvery { evalService.getEval(evalId) } returns expectedEval
        
            // Act
            val response = evalController.getEval(evalId)
        
            // Assert
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(expectedEval, response.body)
            coVerify(exactly = 1) { evalService.getEval(evalId) }
        }

    @Test
    fun `getEval should throw ResponseStatusException when not found`() =
        runBlocking {
            // Arrange
            val evalId = "non-existent-id"
            coEvery { evalService.getEval(evalId) } returns null
        
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalController.getEval(evalId)
                }
        
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation not found with ID: $evalId", exception.reason)
            coVerify(exactly = 1) { evalService.getEval(evalId) }
        }

    @Test
    fun `listEvals should return paginated list of evaluations`() =
        runBlocking {
            // Arrange
            val params =
                ListEvalsParams(
                    limit = 10,
                    order = "desc",
                    after = null,
                    before = null,
                    metadata = null,
                )
        
            val evalList =
                listOf(
                    Eval(id = "eval-1", name = "Eval 1", dataSourceConfig = mockk(), testingCriteria = listOf(mockk())),
                    Eval(id = "eval-2", name = "Eval 2", dataSourceConfig = mockk(), testingCriteria = listOf(mockk())),
                )
        
            val expectedResponse =
                EvalListResponse(
                    data = evalList,
                    hasMore = false,
                    firstId = "eval-1",
                    lastId = "eval-2",
                    limit = 10,
                )
        
            coEvery { 
                evalService.listEvals(any()) 
            } returns expectedResponse
        
            // Act
            val response =
                evalController.listEvals(
                    limit = params.limit,
                    order = params.order,
                    after = params.after,
                    before = params.before,
                    metadata = params.metadata,
                )
        
            // Assert
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(expectedResponse, response.body)
        }

    @Test
    fun `listEvals should throw exception for invalid limit`() =
        runBlocking {
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalController.listEvals(limit = 0, order = "desc", after = null, before = null, metadata = null)
                }
        
            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            assertEquals("Limit must be between 1 and 100", exception.reason)
        }

    @Test
    fun `listEvals should throw exception for invalid order`() =
        runBlocking {
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalController.listEvals(limit = 10, order = "invalid", after = null, before = null, metadata = null)
                }
        
            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            assertEquals("Order must be 'asc' or 'desc'", exception.reason)
        }

    @Test
    fun `deleteEval should return no content when successful`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            coEvery { evalService.deleteEval(evalId) } returns true
        
            // Act
            val response = evalController.deleteEval(evalId)
        
            // Assert
            assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
            assertNotNull(response)
            coVerify(exactly = 1) { evalService.deleteEval(evalId) }
        }

    @Test
    fun `deleteEval should throw ResponseStatusException when not found`() =
        runBlocking {
            // Arrange
            val evalId = "non-existent-id"
            coEvery { evalService.deleteEval(evalId) } returns false
        
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalController.deleteEval(evalId)
                }
        
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation not found with ID: $evalId", exception.reason)
            coVerify(exactly = 1) { evalService.deleteEval(evalId) }
        }

    @Test
    fun `updateEval should return updated evaluation when successful`() =
        runBlocking {
            // Arrange
            val evalId = "eval-123"
            val request =
                UpdateEvalRequest(
                    name = "Updated Eval",
                    metadata = mapOf("new" to "value"),
                )
        
            val updatedEval =
                Eval(
                    id = evalId,
                    name = request.name,
                    dataSourceConfig = mockk(),
                    testingCriteria = listOf(mockk()),
                    metadata = request.metadata,
                )
        
            coEvery { evalService.updateEval(evalId, request) } returns updatedEval
        
            // Act
            val response = evalController.updateEval(evalId, request)
        
            // Assert
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(updatedEval, response.body)
            coVerify(exactly = 1) { evalService.updateEval(evalId, request) }
        }

    @Test
    fun `updateEval should throw ResponseStatusException when not found`() =
        runBlocking {
            // Arrange
            val evalId = "non-existent-id"
            val request = UpdateEvalRequest(name = "Updated Eval")
        
            coEvery { evalService.updateEval(evalId, request) } returns null
        
            // Act & Assert
            val exception =
                assertThrows<ResponseStatusException> {
                    evalController.updateEval(evalId, request)
                }
        
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            assertEquals("Evaluation not found with ID: $evalId", exception.reason)
            coVerify(exactly = 1) { evalService.updateEval(evalId, request) }
        }
} 
