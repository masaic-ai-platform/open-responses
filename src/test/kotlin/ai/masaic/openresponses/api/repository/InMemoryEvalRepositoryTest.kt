package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.Eval
import ai.masaic.openresponses.api.model.StoredCompletionsDataSourceConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryEvalRepositoryTest {

    private lateinit var repository: InMemoryEvalRepository

    @BeforeEach
    fun setUp() {
        repository = InMemoryEvalRepository()
    }

    @Test
    fun `createEval should create and return a new evaluation with ID`() {
        // Given
        val eval = Eval(
            id = "",
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
        
        // When
        val result = repository.createEval(eval)
        
        // Then
        assertNotEquals("", result.id)
        assertTrue(result.id.startsWith("eval_"))
        assertEquals("Test Eval", result.name)
    }
    
    @Test
    fun `getEval should return the evaluation when it exists`() {
        // Given
        val eval = Eval(
            id = "",
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
        
        val createdEval = repository.createEval(eval)
        
        // When
        val result = repository.getEval(createdEval.id)
        
        // Then
        assertNotNull(result)
        assertEquals(createdEval.id, result?.id)
        assertEquals("Test Eval", result?.name)
    }
    
    @Test
    fun `getEval should return null when evaluation does not exist`() {
        // When
        val result = repository.getEval("eval_nonexistent")
        
        // Then
        assertNull(result)
    }
    
    @Test
    fun `listEvals should return all evaluations`() {
        // Given
        val eval1 = Eval(
            id = "",
            objectType = "eval",
            name = "Eval 1",
            createdAt = Instant.now().epochSecond,
            dataSourceConfig = StoredCompletionsDataSourceConfig(
                metadata = mapOf("test" to "value"),
                schema = null
            ),
            testingCriteria = emptyList(),
            shareWithOpenAI = false
        )
        
        val eval2 = Eval(
            id = "",
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
        
        repository.createEval(eval1)
        repository.createEval(eval2)
        
        // When
        val result = repository.listEvals()
        
        // Then
        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "Eval 1" })
        assertTrue(result.any { it.name == "Eval 2" })
    }
    
    @Test
    fun `deleteEval should return true when evaluation is deleted`() {
        // Given
        val eval = Eval(
            id = "",
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
        
        val createdEval = repository.createEval(eval)
        
        // When
        val result = repository.deleteEval(createdEval.id)
        
        // Then
        assertTrue(result)
        assertNull(repository.getEval(createdEval.id))
    }
    
    @Test
    fun `deleteEval should return false when evaluation does not exist`() {
        // When
        val result = repository.deleteEval("eval_nonexistent")
        
        // Then
        assertFalse(result)
    }
} 