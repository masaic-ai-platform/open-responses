package ai.masaic.openresponses.tool

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for serialization and deserialization of tool-related models.
 */
class ToolModelSerializationTest {
    private val json =
        Json { 
            ignoreUnknownKeys = true 
            prettyPrint = false
        }

    @Test
    fun `ToolMetadata should properly serialize`() {
        // Given
        val toolMetadata =
            ToolMetadata(
                id = "tool-id-123",
                name = "test-tool",
                description = "A test tool for testing",
            )
        
        // When
        val serialized = json.encodeToString(toolMetadata)
        
        // Then
        val expected = """{"id":"tool-id-123","name":"test-tool","description":"A test tool for testing"}"""
        assertEquals(expected, serialized)
    }

    @Test
    fun `AIModelInfo should properly serialize`() {
        // Given
        val modelInfo =
            AIModelInfo(
                id = "model-id-123",
                name = "gpt-4",
                description = "A powerful language model",
                provider = "OpenAI",
            )
        
        // When
        val serialized = json.encodeToString(modelInfo)
        
        // Then
        val expected = """{"id":"model-id-123","name":"gpt-4","description":"A powerful language model","provider":"OpenAI"}"""
        assertEquals(expected, serialized)
    }

    @Test
    fun `AIModelsMetadata should properly serialize`() {
        // Given
        val models =
            AIModelsMetadata(
                models =
                    listOf(
                        AIModelInfo(
                            id = "model-1",
                            name = "gpt-4",
                            description = "Advanced model",
                            provider = "OpenAI",
                        ),
                        AIModelInfo(
                            id = "model-2",
                            name = "claude-3",
                            description = "Another model",
                            provider = "Anthropic",
                        ),
                    ),
            )
        
        // When
        val serialized = json.encodeToString(models)
        
        // Then
        assertTrue(serialized.contains("model-1"))
        assertTrue(serialized.contains("gpt-4"))
        assertTrue(serialized.contains("model-2"))
        assertTrue(serialized.contains("claude-3"))
        assertTrue(serialized.contains("OpenAI"))
        assertTrue(serialized.contains("Anthropic"))
    }
} 
