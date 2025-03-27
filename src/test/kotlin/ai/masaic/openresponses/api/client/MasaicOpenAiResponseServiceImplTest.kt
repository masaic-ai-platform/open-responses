package ai.masaic.openresponses.api.client

import com.openai.client.OpenAIClient
import com.openai.core.JsonField
import com.openai.core.RequestOptions
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletion.Choice.FinishReason
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.*
import com.openai.services.blocking.ChatService
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import java.util.NoSuchElementException
import java.util.Optional

class MasaicOpenAiResponseServiceImplTest {
    private lateinit var parameterConverter: MasaicParameterConverter
    private lateinit var toolHandler: MasaicToolHandler
    private lateinit var streamingService: MasaicStreamingService
    private lateinit var responseStore: ResponseStore
    private lateinit var serviceImpl: MasaicOpenAiResponseServiceImpl

    @BeforeEach
    fun setup() {
        parameterConverter = mockk(relaxed = true)
        toolHandler = mockk(relaxed = true)
        streamingService = mockk(relaxed = true)
        responseStore = mockk(relaxed = true)

        serviceImpl =
            MasaicOpenAiResponseServiceImpl(
                parameterConverter = parameterConverter,
                toolHandler = toolHandler,
                streamingService = streamingService,
                responseStore = responseStore,
            )
    }

    /**
     * withRawResponse() should throw UnsupportedOperationException.
     */
    @Test
    fun `test withRawResponse throws`() {
        assertThrows(UnsupportedOperationException::class.java) {
            serviceImpl.withRawResponse()
        }
    }

    /**
     * inputItems() should throw UnsupportedOperationException.
     */
    @Test
    fun `test inputItems throws`() {
        assertThrows(UnsupportedOperationException::class.java) {
            serviceImpl.inputItems()
        }
    }

    /**
     * create(params, requestOptions) should throw UnsupportedOperationException.
     */
    @Test
    fun `test create with RequestOptions throws`() {
        val params = mockk<ResponseCreateParams>(relaxed = true)
        val options = mockk<RequestOptions>(relaxed = true)

        assertThrows(UnsupportedOperationException::class.java) {
            serviceImpl.create(params, options)
        }
    }

    /**
     * create(client, params) tests:
     * 1) No tool calls -> return direct response
     * 2) Has tool calls -> calls handleMasaicToolCall and recurses
     * 3) Too many tool calls -> throws IllegalArgumentException
     */
    @Test
    fun `test create with no tool calls returns direct response`() {
        // Setup
        val client = mockk<OpenAIClient>(relaxed = true)
        val params = defaultParamsMock()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.instructions() } returns Optional.empty()

        // Mock the completion response from OpenAI
        val completion = mockk<ChatCompletion>(relaxed = true)
        every { completion.id() } returns "chatcmpl-1"
        val choice = mockk<ChatCompletion.Choice>(relaxed = true)
        // No tool calls => finish reason is something else
        every { choice.finishReason() } returns FinishReason.STOP
        every { completion.choices() } returns listOf(choice)
        every { completion.usage() } returns Optional.empty()

        // Mock client.chat().completions().create(...) => returns 'completion'
        val mockChat = mockk<ChatService>(relaxed = true)
        val mockCompletions = mockk<com.openai.services.blocking.chat.ChatCompletionService>(relaxed = true)
        every { client.chat() } returns mockChat
        every { mockChat.completions() } returns mockCompletions
        every { mockCompletions.create(any()) } returns completion

        // Mock parameterConverter
        val preparedParams = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(params) } returns preparedParams

        // Act
        val result = serviceImpl.create(client, params)

        // Assert
        // Should return directly, no recursion
        assertNotNull(result)
        verify(exactly = 1) { mockCompletions.create(preparedParams) }
        // confirm we never called toolHandler
        verify { toolHandler wasNot Called }
        verify { responseStore wasNot Called }
    }

    /**
     * create(client, params) tests:
     * 1) No tool calls -> return direct response
     * 2) Has tool calls -> calls handleMasaicToolCall and recurses
     * 3) Too many tool calls -> throws IllegalArgumentException
     */
    @Test
    fun `test create with no tool calls returns direct response with store`() {
        // Setup
        val client = mockk<OpenAIClient>(relaxed = true)
        val params = defaultParamsMock(true)
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.instructions() } returns Optional.empty()

        // Mock the completion response from OpenAI
        val completion = mockk<ChatCompletion>(relaxed = true)
        every { completion.id() } returns "chatcmpl-1"
        val choice = mockk<ChatCompletion.Choice>(relaxed = true)
        // No tool calls => finish reason is something else
        every { choice.finishReason() } returns FinishReason.STOP
        every { completion.choices() } returns listOf(choice)
        every { completion.usage() } returns Optional.empty()

        // Mock client.chat().completions().create(...) => returns 'completion'
        val mockChat = mockk<ChatService>(relaxed = true)
        val mockCompletions = mockk<com.openai.services.blocking.chat.ChatCompletionService>(relaxed = true)
        every { client.chat() } returns mockChat
        every { mockChat.completions() } returns mockCompletions
        every { mockCompletions.create(any()) } returns completion

        // Mock parameterConverter
        val preparedParams = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(params) } returns preparedParams

        // Act
        val result = serviceImpl.create(client, params)

        // Assert
        // Should return directly, no recursion
        assertNotNull(result)
        verify(exactly = 1) { mockCompletions.create(preparedParams) }
        // confirm we never called toolHandler
        verify { toolHandler wasNot Called }
        verify { responseStore.storeResponse(any(), any()) }
    }

    @Test
    fun `test create with tool calls triggers handleMasaicToolCall and recursion`() {
        // 1) Create and stub the ORIGINAL params
        val originalParams = mockk<ResponseCreateParams>(relaxed = true)
        // Provide all stubs your code might call
        every { originalParams.instructions() } returns Optional.of("Some instructions")
        every { originalParams.metadata() } returns Optional.empty()
        every { originalParams.model() } returns ChatModel.of("gpt-4")
        every { originalParams.previousResponseId() } returns Optional.empty()
        every { originalParams.reasoning() } returns Optional.empty()
        every { originalParams.temperature() } returns Optional.of(0.0)
        every { originalParams.topP() } returns Optional.of(0.0)
        every { originalParams.toolChoice() } returns Optional.empty()

        // ...
        // For the first iteration, assume it's a text-based input or whatever your code expects
        val firstInput = mockk<ResponseCreateParams.Input>(relaxed = true)
        every { firstInput.isText() } returns true
        every { originalParams.input() } returns firstInput

        // 2) Create and stub the NEW params used in the second iteration
        val newParams = mockk<ResponseCreateParams>(relaxed = true)
        every { newParams.instructions() } returns Optional.of("Some instructions")
        every { newParams.metadata() } returns Optional.empty()
        every { newParams.model() } returns ChatModel.of("gpt-4")
        every { newParams.previousResponseId() } returns Optional.empty()
        every { newParams.reasoning() } returns Optional.empty()
        every { newParams.temperature() } returns Optional.of(0.0)
        every { newParams.toolChoice() } returns Optional.empty()
        every { newParams.topP() } returns Optional.of(0.0)
        every { newParams.maxOutputTokens() } returns Optional.of(100)

        // ...
        // The second iteration might have a different input if your code sets it
        val secondInput = mockk<ResponseCreateParams.Input>(relaxed = true)
        every { secondInput.isResponse() } returns true
        every { newParams.input() } returns secondInput

        // 3) Mock the builder usage so .toBuilder().input(...).build() => newParams
        val mockBuilder = mockk<ResponseCreateParams.Builder>(relaxed = false)
        every { originalParams.toBuilder() } returns mockBuilder
        // If your code sets the input, ensure stubbing returns mockBuilder
        every { mockBuilder.input(ofType<ResponseCreateParams.Input>()) } returns mockBuilder
        // Then build() returns the newParams
        every { mockBuilder.build() } returns newParams

        // 4) Mock the OpenAI client's chat/completion calls.
        val client = mockk<OpenAIClient>(relaxed = true)
        val mockChat = mockk<com.openai.services.blocking.ChatService>(relaxed = true)
        val mockCompletions = mockk<com.openai.services.blocking.chat.ChatCompletionService>(relaxed = true)
        every { client.chat() } returns mockChat
        every { mockChat.completions() } returns mockCompletions

        // 5) We simulate a finish reason containing tool_calls in the FIRST iteration
        val firstCompletion = mockk<ChatCompletion>(relaxed = true)
        val firstChoice = mockk<ChatCompletion.Choice>(relaxed = true)
        every { firstChoice.finishReason() } returns ChatCompletion.Choice.FinishReason.TOOL_CALLS
        every { firstCompletion.choices() } returns listOf(firstChoice)

        // The SECOND iteration returns a final completion with STOP (no tool calls).
        val secondCompletion = mockk<ChatCompletion>(relaxed = true)
        val secondChoice = mockk<ChatCompletion.Choice>(relaxed = true)
        every { secondChoice.finishReason() } returns ChatCompletion.Choice.FinishReason.STOP
        every { secondCompletion.choices() } returns listOf(secondChoice)
        every { secondCompletion.usage() } returns Optional.empty()

        var createCallCount = 0
        every { mockCompletions.create(any()) } answers {
            createCallCount++
            if (createCallCount == 1) firstCompletion else secondCompletion
        }

        // 6) The toolHandler returns new input items for the 2nd iteration
        val newInputItems =
            listOf(
                mockk<ResponseInputItem>(relaxed = true),
            )
        every { toolHandler.handleMasaicToolCall(firstCompletion, originalParams) } returns newInputItems

        // 7) Stub parameter converter to return a ChatCompletionCreateParams for each iteration
        val preparedParams1 = mockk<ChatCompletionCreateParams>(relaxed = true)
        val preparedParams2 = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(originalParams) } returns preparedParams1
        every { parameterConverter.prepareCompletion(newParams) } returns preparedParams2

        // 8) Call the method under test
        val finalResponse = serviceImpl.create(client, originalParams)

        // 9) Verify
        assertNotNull(finalResponse)
        assertEquals(2, createCallCount, "Expected two create(...) calls (two iterations)")

        // Confirm we handled a tool call in the first iteration
        verify(exactly = 1) { toolHandler.handleMasaicToolCall(firstCompletion, originalParams) }

        // Confirm second iteration used newParams, etc.
        verify { parameterConverter.prepareCompletion(newParams) }
    }

    @Test
    fun `test create with too many tool calls throws IllegalArgumentException`() {
        val client = mockk<OpenAIClient>(relaxed = true)
        val params = mockk<ResponseCreateParams>(relaxed = true)
        val completion = mockk<ChatCompletion>(relaxed = true)
        val choice = mockk<ChatCompletion.Choice>(relaxed = true)
        every { choice.finishReason() } returns ChatCompletion.Choice.FinishReason.TOOL_CALLS
        every { completion.choices() } returns listOf(choice)

        // Mock client, completions
        val mockChat = mockk<ChatService>(relaxed = true)
        val mockCompletions = mockk<com.openai.services.blocking.chat.ChatCompletionService>(relaxed = true)
        every { client.chat() } returns mockChat
        every { mockChat.completions() } returns mockCompletions
        every { mockCompletions.create(any()) } returns completion

        // The toolHandler returns new input items with 11 function calls, exceeding default limit 10
        val tooManyCalls =
            (1..11).map {
                mockk<ResponseInputItem>(relaxed = true).apply {
                    every { isFunctionCall() } returns true
                }
            }
        every { toolHandler.handleMasaicToolCall(completion, params) } returns tooManyCalls

        // Mock builder usage
        val newParams = mockk<ResponseCreateParams>(relaxed = true)
        val builder = mockk<ResponseCreateParams.Builder>(relaxed = true)
        every { params.toBuilder() } returns builder
        every { builder.input(ofType<ResponseCreateParams.Input>()) } returns builder
        every { builder.build() } returns newParams

        // Stub parameter converter
        val preparedParams = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(params) } returns preparedParams

        // Act / Assert
        assertThrows(IllegalArgumentException::class.java) {
            serviceImpl.create(client, params)
        }
    }

    /**
     * createCompletionStream should delegate to streamingService.createCompletionStream
     */
    @Test
    fun `test createCompletionStream`() {
        val client = mockk<OpenAIClient>(relaxed = true)
        val params = mockk<ResponseCreateParams>(relaxed = true)
        val flowMock = mockk<Flow<ServerSentEvent<String>>>(relaxed = true)

        every { streamingService.createCompletionStream(client, params) } returns flowMock

        val resultFlow = serviceImpl.createCompletionStream(client, params)
        assertSame(flowMock, resultFlow)
        verify { streamingService.createCompletionStream(client, params) }
    }

    /**
     * createStreaming(params, requestOptions) -> throws UnsupportedOperationException
     */
    @Test
    fun `test createStreaming throws`() {
        val params = mockk<ResponseCreateParams>(relaxed = true)
        val options = mockk<RequestOptions>(relaxed = true)

        assertThrows(UnsupportedOperationException::class.java) {
            serviceImpl.createStreaming(params, options)
        }
    }

    /**
     * Test retrieve method successfully returns a response from the store.
     */
    @Test
    fun `test retrieve returns response from store`() {
        // Setup
        val responseId = "resp_123456"
        val params = mockk<ResponseRetrieveParams>(relaxed = true)
        every { params.responseId() } returns responseId
        
        val mockResponse = mockk<Response>(relaxed = true)
        every { responseStore.getResponse(responseId) } returns mockResponse
        
        val options = mockk<RequestOptions>(relaxed = true)
        
        // Act
        val result = serviceImpl.retrieve(params, options)
        
        // Assert
        assertNotNull(result)
        assertEquals(mockResponse, result)
        verify(exactly = 1) { responseStore.getResponse(responseId) }
    }

    /**
     * Test retrieve method throws when response not found.
     */
    @Test
    fun `test retrieve throws when response not found`() {
        // Setup
        val responseId = "nonexistent_resp"
        val params = mockk<ResponseRetrieveParams>(relaxed = true)
        every { params.responseId() } returns responseId
        
        every { responseStore.getResponse(responseId) } returns null
        
        val options = mockk<RequestOptions>(relaxed = true)
        
        // Act & Assert
        assertThrows(NoSuchElementException::class.java) {
            serviceImpl.retrieve(params, options)
        }
        verify(exactly = 1) { responseStore.getResponse(responseId) }
    }

    /**
     * Test delete method calls responseStore.deleteResponse.
     */
    @Test
    fun `test delete calls responseStore deleteResponse`() {
        // Setup
        val responseId = "resp_123456"
        val params = mockk<ResponseDeleteParams>(relaxed = true)
        every { params.responseId() } returns responseId
        
        every { responseStore.deleteResponse(responseId) } returns true
        
        val options = mockk<RequestOptions>(relaxed = true)
        
        // Act
        serviceImpl.delete(params, options)
        
        // Assert
        verify(exactly = 1) { responseStore.deleteResponse(responseId) }
    }

    /**
     * Utility method that returns a partially mocked ResponseCreateParams with minimal needed fields.
     */
    private fun defaultParamsMock(store: Boolean = false): ResponseCreateParams {
        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList<Tool>())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()
        every { params.store() } returns Optional.of(store)
        // By default, create a text-based input
        val mockInput =
            mockk<ResponseCreateParams.Input> {
                every { isResponse() } returns false
                every { isText() } returns true
                every { asText() } returns "Hello world"
            }
        return params
    }
}
