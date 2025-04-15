package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.repository.EvalRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.time.Instant

class EvalServiceTest {

    private lateinit var evalRepository: EvalRepository
    private lateinit var evalService: EvalService

    @BeforeEach
    fun setUp() {
        evalRepository = mock(EvalRepository::class.java)
        evalService = EvalService(evalRepository)
    }

    @Test
    fun `createEval should create and return a new evaluation`() {
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
        
        val createdEval = Eval(
            id = "eval_12345",
            objectType = "eval",
            name = "Sentiment",
            createdAt = Instant.now().epochSecond,
            dataSourceConfig = dataSourceConfig,
            testingCriteria = testingCriteria,
            shareWithOpenAI = false
        )
        
        `when`(evalRepository.createEval(any())).thenReturn(createdEval)
        
        // When
        val result = evalService.createEval(createRequest)
        
        // Then
        assertEquals("eval_12345", result.id)
        assertEquals("Sentiment", result.name)
        assertEquals(dataSourceConfig, result.dataSourceConfig)
        assertEquals(testingCriteria, result.testingCriteria)
        verify(evalRepository, times(1)).createEval(any())
    }
    
    @Test
    fun `getEval should return the evaluation when it exists`() {
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
        
        `when`(evalRepository.getEval(evalId)).thenReturn(eval)
        
        // When
        val result = evalService.getEval(evalId)
        
        // Then
        assertNotNull(result)
        assertEquals(evalId, result?.id)
        assertEquals("Test Eval", result?.name)
    }
    
    @Test
    fun `getEval should return null when evaluation does not exist`() {
        // Given
        val evalId = "eval_nonexistent"
        
        `when`(evalRepository.getEval(evalId)).thenReturn(null)
        
        // When
        val result = evalService.getEval(evalId)
        
        // Then
        assertNull(result)
    }
    
    @Test
    fun `listEvals should return all evaluations`() {
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
        
        `when`(evalRepository.listEvals()).thenReturn(evals)
        
        // When
        val result = evalService.listEvals()
        
        // Then
        assertEquals(2, result.size)
        assertEquals("eval_1", result[0].id)
        assertEquals("eval_2", result[1].id)
    }
    
    @Test
    fun `deleteEval should return true when evaluation is deleted`() {
        // Given
        val evalId = "eval_12345"
        
        `when`(evalRepository.deleteEval(evalId)).thenReturn(true)
        
        // When
        val result = evalService.deleteEval(evalId)
        
        // Then
        assertTrue(result)
        verify(evalRepository, times(1)).deleteEval(evalId)
    }
    
    @Test
    fun `deleteEval should return false when evaluation does not exist`() {
        // Given
        val evalId = "eval_nonexistent"
        
        `when`(evalRepository.deleteEval(evalId)).thenReturn(false)
        
        // When
        val result = evalService.deleteEval(evalId)
        
        // Then
        assertFalse(result)
        verify(evalRepository, times(1)).deleteEval(evalId)
    }
} 