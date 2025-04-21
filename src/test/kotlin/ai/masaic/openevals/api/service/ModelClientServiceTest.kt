package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.CompletionResult
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletion.Choice
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.chat.ChatCompletionService
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ModelClientServiceTest {
    private lateinit var service: ModelClientService
    private lateinit var mockClient: OpenAIClient
    private lateinit var mockChat: ChatService
    private lateinit var mockCompletions: ChatCompletionService

    @BeforeEach
    fun setUp() {
        // Create a fresh instance for each test
        service = ModelClientService()
        
        // Mock OpenAI client components
        mockClient = mockk<OpenAIClient>(relaxed = true)
        mockChat = mockk<ChatService>(relaxed = true)
        mockCompletions = mockk<ChatCompletionService>(relaxed = true)
        
        // Set up the mocking chain
        every { mockClient.chat() } returns mockChat
        every { mockChat.completions() } returns mockCompletions
    }

    @Test
    fun `getOpenAIClient should cache clients by API key`() {
        // Instead of trying to mock the private method directly, we'll use a different approach
        // that directly tests the caching behavior
        
        // Create client cache as a spy to be able to track its behavior
        val serviceWithSpy = spyk(service)
        
        // Add a mock client to the cache for our test key manually (using reflection)
        val clientCacheField = ModelClientService::class.java.getDeclaredField("clientCache")
        clientCacheField.isAccessible = true
        val clientCache = clientCacheField.get(serviceWithSpy) as ConcurrentHashMap<String, OpenAIClient>
        clientCache["test-api-key"] = mockClient
        
        // Get the client twice with the same key
        val client1 = serviceWithSpy.getOpenAIClient("test-api-key", "test-model")
        val client2 = serviceWithSpy.getOpenAIClient("test-api-key", "test-model")
        
        // Verify both references are to the same client
        assertSame(client1, client2)
        assertSame(mockClient, client1)
    }

    @Test
    fun `createBasicCompletionParams should create builder with correct model`() {
        // Act
        val builder = service.createBasicCompletionParams("gpt-4")
        val params =
            builder
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionUserMessageParam
                        .builder()
                        .content("Test message")
                        .build(),
                ).build()

        // Assert
        assertEquals("gpt-4", params.model().asString())
    }

    @Test
    fun `extractCompletionContent should get content from first choice`() {
        // Arrange - Create a mock completion with content
        val completion = mockk<ChatCompletion>()
        val choice = mockk<Choice>()
        val message = mockk<ChatCompletionMessage>()
        
        every { completion.choices() } returns listOf(choice)
        every { choice.message() } returns message
        every { message.content() } returns Optional.of("Test content")
        
        // Act
        val content = service.extractCompletionContent(completion)
        
        // Assert
        assertEquals("Test content", content)
    }

    @Test
    fun `extractCompletionContent should handle empty content`() {
        // Arrange - Create a mock completion with empty content
        val completion = mockk<ChatCompletion>()
        val choice = mockk<Choice>()
        val message = mockk<ChatCompletionMessage>()
        
        every { completion.choices() } returns listOf(choice)
        every { choice.message() } returns message
        every { message.content() } returns Optional.empty()
        
        // Act
        val content = service.extractCompletionContent(completion)
        
        // Assert
        assertEquals("", content)
    }

    @Test
    fun `extractCompletionContent should handle empty choices`() {
        // Arrange - Create a mock completion with no choices
        val completion = mockk<ChatCompletion>()
        
        every { completion.choices() } returns emptyList()
        
        // Act
        val content = service.extractCompletionContent(completion)
        
        // Assert
        assertEquals("", content)
    }

    @Test
    fun `executeCompletionCall should call API and extract content`() {
        // Arrange
        val params =
            ChatCompletionCreateParams
                .builder()
                .model("gpt-4")
                // Add required message using ChatCompletionUserMessageParam
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionUserMessageParam
                        .builder()
                        .content("Test message")
                        .build(),
                ).build()
            
        val completion = mockk<ChatCompletion>()
        val choice = mockk<Choice>()
        val message = mockk<ChatCompletionMessage>()
        
        every { mockCompletions.create(any()) } returns completion
        every { completion.choices() } returns listOf(choice)
        every { choice.message() } returns message
        every { message.content() } returns Optional.of("API response")
        
        // Act
        val content = service.executeCompletionCall(mockClient, params)
        
        // Assert
        assertEquals("API response", content)
        verify { mockCompletions.create(params) }
    }

    @Test
    fun `executeCompletionWithErrorHandling should handle successful completion`() {
        // Arrange
        val params =
            ChatCompletionCreateParams
                .builder()
                .model("gpt-4")
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionUserMessageParam
                        .builder()
                        .content("Test message")
                        .build(),
                ).build()
            
        val completion = mockk<ChatCompletion>()
        val choice = mockk<Choice>()
        val message = mockk<ChatCompletionMessage>()
        
        every { mockCompletions.create(any()) } returns completion
        every { completion.choices() } returns listOf(choice)
        every { choice.message() } returns message
        every { message.content() } returns Optional.of("Success response")
        
        // Act
        val result =
            service.executeCompletionWithErrorHandling(
                mockClient,
                params,
                "test",
            ) { content, error -> 
                Pair(content, error)
            }
        
        // Assert
        assertEquals("Success response", result.first)
        assertEquals(null, result.second)
    }

    @Test
    fun `executeCompletionWithErrorHandling should handle exception`() {
        // Arrange
        val params =
            ChatCompletionCreateParams
                .builder()
                .model("gpt-4")
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionUserMessageParam
                        .builder()
                        .content("Test message")
                        .build(),
                ).build()
            
        // Make the API call throw an exception
        every { mockCompletions.create(any()) } throws RuntimeException("API error")
        
        // Act
        val result =
            service.executeCompletionWithErrorHandling(
                mockClient,
                params,
                "test",
            ) { content, error -> 
                Pair(content, error)
            }
        
        // Assert
        assertEquals("", result.first)
        assertEquals("API error", result.second)
    }

    @Test
    fun `executeWithClientAndErrorHandling should handle successful completion`() {
        // Instead of spying and mocking getOpenAIClient, add our mock to the cache directly
        val clientCacheField = ModelClientService::class.java.getDeclaredField("clientCache")
        clientCacheField.isAccessible = true
        val clientCache = clientCacheField.get(service) as ConcurrentHashMap<String, OpenAIClient>
        clientCache["test-api-key"] = mockClient
        
        val params =
            ChatCompletionCreateParams
                .builder()
                .model("gpt-4")
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionUserMessageParam
                        .builder()
                        .content("Test message")
                        .build(),
                ).build()
            
        val completion = mockk<ChatCompletion>()
        val choice = mockk<Choice>()
        val message = mockk<ChatCompletionMessage>()
        
        every { mockCompletions.create(any()) } returns completion
        every { completion.choices() } returns listOf(choice)
        every { choice.message() } returns message
        every { message.content() } returns Optional.of("Full success response")
        
        // Act
        val result =
            service.executeWithClientAndErrorHandling(
                apiKey = "test-api-key",
                params = params,
                identifier = "test-id",
            ) { content, error ->
                CompletionResult(
                    contentJson = content,
                    error = error,
                )
            }
        
        // Assert
        assertEquals("Full success response", result.contentJson)
        assertEquals(null, result.error)
    }

    @Test
    fun `executeWithClientAndErrorHandling should handle exception`() {
        // Instead of spying and mocking getOpenAIClient, add our mock to the cache directly
        val clientCacheField = ModelClientService::class.java.getDeclaredField("clientCache")
        clientCacheField.isAccessible = true
        val clientCache = clientCacheField.get(service) as ConcurrentHashMap<String, OpenAIClient>
        clientCache["test-api-key"] = mockClient
        
        val params =
            ChatCompletionCreateParams
                .builder()
                .model("gpt-4")
                .addMessage(
                    com.openai.models.chat.completions.ChatCompletionUserMessageParam
                        .builder()
                        .content("Test message")
                        .build(),
                ).build()
            
        // Make the API call throw an exception
        every { mockCompletions.create(any()) } throws RuntimeException("Full API error")
        
        // Act
        val result =
            service.executeWithClientAndErrorHandling(
                apiKey = "test-api-key",
                params = params,
                identifier = "test-id",
            ) { content, error ->
                CompletionResult(
                    contentJson = content,
                    error = error,
                )
            }
        
        // Assert
        assertEquals("", result.contentJson)
        assertEquals("Full API error", result.error)
    }
} 
