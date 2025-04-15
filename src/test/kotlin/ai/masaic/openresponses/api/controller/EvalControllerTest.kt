package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.EvalService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
import java.util.*

@WebMvcTest(EvalController::class)
class EvalControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var evalService: EvalService

    @Test
    fun `creating a new evaluation returns created eval`() {
        // Given
        val dataSourceConfig = StoredCompletionsDataSourceConfig(
            metadata = mapOf("usecase" to "chatbot"),
            schema = null
        )
        
        val testingCriteria = listOf(
            LabelModelGrader(
                name = "Example label grader",
                model = "o3-mini",
                input = listOf(
                    mapOf(
                        "role" to "developer",
                        "content" to "Classify the sentiment of the following statement as one of 'positive', 'neutral', or 'negative'"
                    ),
                    mapOf(
                        "role" to "user",
                        "content" to "Statement: {{item.input}}"
                    )
                ),
                labels = listOf("positive", "neutral", "negative"),
                passingLabels = listOf("positive")
            )
        )
        
        val createRequest = CreateEvalRequest(
            name = "Sentiment",
            dataSourceConfig = dataSourceConfig,
            testingCriteria = testingCriteria,
            shareWithOpenAI = false
        )
        
        val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
        val createdAt = Instant.now().epochSecond
        
        val expectedEval = Eval(
            id = evalId,
            objectType = "eval",
            name = "Sentiment",
            createdAt = createdAt,
            dataSourceConfig = dataSourceConfig,
            testingCriteria = testingCriteria,
            shareWithOpenAI = false
        )
        
        `when`(evalService.createEval(createRequest)).thenReturn(expectedEval)
        
        // When & Then
        mockMvc.perform(
            post("/v1/evals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(evalId))
            .andExpect(jsonPath("$.name").value("Sentiment"))
            .andExpect(jsonPath("$.object").value("eval"))
    }
    
    @Test
    fun `get evaluation by ID returns eval when exists`() {
        // Given
        val evalId = "eval_12345"
        val eval = Eval(
            id = evalId,
            objectType = "eval",
            name = "Test Eval",
            createdAt = Instant.now().epochSecond,
            dataSourceConfig = StoredCompletionsDataSourceConfig(
                metadata = mapOf("test" to "value"),
                schema = null
            ),
            testingCriteria = emptyList(),
            shareWithOpenAI = false
        )
        
        `when`(evalService.getEval(evalId)).thenReturn(eval)
        
        // When & Then
        mockMvc.perform(get("/v1/evals/$evalId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(evalId))
            .andExpect(jsonPath("$.name").value("Test Eval"))
    }
    
    @Test
    fun `get evaluation by ID returns 404 when not exists`() {
        // Given
        val evalId = "eval_nonexistent"
        
        `when`(evalService.getEval(evalId)).thenReturn(null)
        
        // When & Then
        mockMvc.perform(get("/v1/evals/$evalId"))
            .andExpect(status().isNotFound)
    }
    
    @Test
    fun `list evaluations returns paginated results`() {
        // Given
        val evals = listOf(
            Eval(
                id = "eval_1",
                objectType = "eval",
                name = "Eval 1",
                createdAt = Instant.now().epochSecond,
                dataSourceConfig = StoredCompletionsDataSourceConfig(
                    metadata = mapOf("test" to "value"),
                    schema = null
                ),
                testingCriteria = emptyList(),
                shareWithOpenAI = false
            ),
            Eval(
                id = "eval_2",
                objectType = "eval",
                name = "Eval 2",
                createdAt = Instant.now().epochSecond,
                dataSourceConfig = StoredCompletionsDataSourceConfig(
                    metadata = mapOf("test" to "value"),
                    schema = null
                ),
                testingCriteria = emptyList(),
                shareWithOpenAI = false
            )
        )
        
        val params = ListEvalsParams(
            limit = 20,
            order = "desc",
            after = null,
            before = null,
            metadata = null
        )
        
        val listResponse = EvalListResponse(
            objectType = "list",
            data = evals,
            hasMore = false,
            firstId = "eval_1",
            lastId = "eval_2",
            limit = 20
        )
        
        `when`(evalService.listEvals(params)).thenReturn(listResponse)
        
        // When & Then
        mockMvc.perform(get("/v1/evals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].id").value("eval_1"))
            .andExpect(jsonPath("$.data[1].id").value("eval_2"))
            .andExpect(jsonPath("$.has_more").value(false))
            .andExpect(jsonPath("$.first_id").value("eval_1"))
            .andExpect(jsonPath("$.last_id").value("eval_2"))
            .andExpect(jsonPath("$.limit").value(20))
    }
    
    @Test
    fun `list evaluations with parameters returns filtered results`() {
        // Given
        val evalMetadata = mapOf("category" to "sentiment")
        
        val evals = listOf(
            Eval(
                id = "eval_1",
                objectType = "eval",
                name = "Eval 1",
                createdAt = Instant.now().epochSecond,
                dataSourceConfig = StoredCompletionsDataSourceConfig(
                    metadata = mapOf("test" to "value"),
                    schema = null
                ),
                testingCriteria = emptyList(),
                metadata = evalMetadata,
                shareWithOpenAI = false
            )
        )
        
        val params = ListEvalsParams(
            limit = 10,
            order = "asc",
            after = "eval_0",
            before = null,
            metadata = evalMetadata
        )
        
        val listResponse = EvalListResponse(
            objectType = "list",
            data = evals,
            hasMore = false,
            firstId = "eval_1",
            lastId = "eval_1",
            limit = 10
        )
        
        `when`(evalService.listEvals(params)).thenReturn(listResponse)
        
        // When & Then
        mockMvc.perform(
            get("/v1/evals")
                .param("limit", "10")
                .param("order", "asc")
                .param("after", "eval_0")
                .param("metadata[category]", "sentiment")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value("eval_1"))
            .andExpect(jsonPath("$.has_more").value(false))
            .andExpect(jsonPath("$.limit").value(10))
    }
    
    @Test
    fun `delete evaluation returns 204 when successful`() {
        // Given
        val evalId = "eval_12345"
        
        `when`(evalService.deleteEval(evalId)).thenReturn(true)
        
        // When & Then
        mockMvc.perform(delete("/v1/evals/$evalId"))
            .andExpect(status().isNoContent)
    }
    
    @Test
    fun `delete evaluation returns 404 when not found`() {
        // Given
        val evalId = "eval_nonexistent"
        
        `when`(evalService.deleteEval(evalId)).thenReturn(false)
        
        // When & Then
        mockMvc.perform(delete("/v1/evals/$evalId"))
            .andExpect(status().isNotFound)
    }
} 