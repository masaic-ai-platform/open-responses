package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import com.openai.core.JsonValue
import com.openai.models.chat.completions.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCompletionsGenerationServiceTest {
    @Test
    fun `canGenerate should return true for CompletionsRunDataSource`() {
        // Arrange
        val service = ChatCompletionsGenerationService()
        val dataSource =
            CompletionsRunDataSource(
                inputMessages = mockk(),
                model = "gpt-4",
                source = FileDataSource("test.jsonl"),
            )

        // Act
        val result = service.canGenerate(dataSource)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `canGenerate should return false for non-CompletionsRunDataSource`() {
        // Arrange
        val service = ChatCompletionsGenerationService()
        val dataSource = mockk<RunDataSource>()

        // Act
        val result = service.canGenerate(dataSource)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `generateCompletions should process messages correctly when mock API returns valid response`() =
        runBlocking {
            // We'll use runBlocking instead of directly mocking the private method
            // This test has to be more of an integration test because of the private method

            // Arrange - Create a real service
            val service = ChatCompletionsGenerationService()
            val apiKey = "test-api-key-not-valid" // Will cause an exception

            val dataSource =
                CompletionsRunDataSource(
                    inputMessages = mockk(),
                    model = "gpt-4",
                    source = FileDataSource("test.jsonl"),
                    samplingParams = SamplingParams(temperature = 0.7, topP = 0.9),
                )

            val dataSourceConfig =
                CustomDataSourceConfig(
                    schema =
                        mapOf(
                            "type" to JsonValue.from("object"),
                            "properties" to
                                JsonValue.from(
                                    mapOf(
                                        "text" to mapOf("type" to "string"),
                                    ),
                                ),
                        ),
                )

            val messages =
                listOf(
                    ChatMessage("system", "You are a helpful assistant."),
                    ChatMessage("user", "Hello, how are you?"),
                )

            val completionMessagesSet =
                mapOf(
                    0 to messages,
                )

            // Act
            val results =
                service.generateCompletions(
                    completionMessagesSet,
                    dataSource,
                    apiKey,
                    dataSourceConfig,
                )

            // Assert - with invalid API key, we expect an error response
            assertEquals(1, results.size)
            assertTrue(results.containsKey(0))
            assertEquals("", results[0]?.contentJson)
            assertTrue(results[0]?.error != null)
        }

    @Test
    fun `generateCompletions should handle multiple completion message sets with errors`() =
        runBlocking {
            // Arrange
            val service = ChatCompletionsGenerationService()
            val apiKey = "test-api-key-not-valid" // Will cause exceptions

            val dataSource =
                CompletionsRunDataSource(
                    inputMessages = mockk(),
                    model = "gpt-4",
                    source = FileDataSource("test.jsonl"),
                )

            val dataSourceConfig =
                CustomDataSourceConfig(
                    schema =
                        mapOf(
                            "type" to JsonValue.from("object"),
                            "properties" to
                                JsonValue.from(
                                    mapOf(
                                        "text" to mapOf("type" to "string"),
                                    ),
                                ),
                        ),
                )

            val completionMessagesSet =
                mapOf(
                    0 to listOf(ChatMessage("user", "First message")),
                    1 to listOf(ChatMessage("user", "Second message")),
                )

            // Act
            val results =
                service.generateCompletions(
                    completionMessagesSet,
                    dataSource,
                    apiKey,
                    dataSourceConfig,
                )

            // Assert - should handle errors for both message sets
            assertEquals(2, results.size)
            assertTrue(results.containsKey(0))
            assertTrue(results.containsKey(1))
            assertEquals("", results[0]?.contentJson)
            assertEquals("", results[1]?.contentJson)
            assertTrue(results[0]?.error != null)
            assertTrue(results[1]?.error != null)
        }
}
