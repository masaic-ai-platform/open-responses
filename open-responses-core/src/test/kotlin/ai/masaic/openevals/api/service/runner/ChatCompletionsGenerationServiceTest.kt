package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.service.ModelClientService
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatCompletionsGenerationServiceTest {
    private val mockModelClientService = mockk<ModelClientService>()
    private val mockClient = mockk<OpenAIClient>()

    @Test
    fun `canGenerate should return true for CompletionsRunDataSource`() {
        // Arrange
        val service = ChatCompletionsGenerationService(mockModelClientService)
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
        val service = ChatCompletionsGenerationService(mockModelClientService)
        val dataSource = mockk<RunDataSource>()

        // Act
        val result = service.canGenerate(dataSource)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `generateCompletions should process messages correctly when mock API returns valid response`() =
        runBlocking {
            // Arrange - Create a service with mocked client service
            val service = ChatCompletionsGenerationService(mockModelClientService)
            val apiKey = "test-api-key-not-valid"

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
                
            // Mock the ModelClientService to return a CompletionResult with an error
            every { 
                mockModelClientService.executeWithClientAndErrorHandling(
                    apiKey = any(),
                    params = any(),
                    identifier = any(),
                    resultBuilder = any(),
                )
            } returns
                ai.masaic.openevals.api.model.CompletionResult(
                    contentJson = "",
                    error = "Test error",
                )

            // Act
            val results =
                service.generateCompletions(
                    completionMessagesSet,
                    dataSource,
                    apiKey,
                    dataSourceConfig,
                )

            // Assert
            assertEquals(1, results.size)
            assertTrue(results.containsKey(0))
            assertEquals("", results[0]?.contentJson)
            assertTrue(results[0]?.error != null)
        }

    @Test
    fun `generateCompletions should handle multiple completion message sets with errors`() =
        runBlocking {
            // Arrange
            val service = ChatCompletionsGenerationService(mockModelClientService)
            val apiKey = "test-api-key-not-valid"

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

            // Mock the ModelClientService to return a CompletionResult with an error
            every { 
                mockModelClientService.executeWithClientAndErrorHandling(
                    apiKey = any(),
                    params = any(),
                    identifier = any(),
                    resultBuilder = any(),
                )
            } returns
                ai.masaic.openevals.api.model.CompletionResult(
                    contentJson = "",
                    error = "Test error",
                )

            // Act
            val results =
                service.generateCompletions(
                    completionMessagesSet,
                    dataSource,
                    apiKey,
                    dataSourceConfig,
                )

            // Assert
            assertEquals(2, results.size)
            assertTrue(results.containsKey(0))
            assertTrue(results.containsKey(1))
            assertEquals("", results[0]?.contentJson)
            assertEquals("", results[1]?.contentJson)
            assertTrue(results[0]?.error != null)
            assertTrue(results[1]?.error != null)
        }
}
