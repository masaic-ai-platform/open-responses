package com.masaic.openai.api.client

import com.masaic.openai.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.core.JsonField
import com.openai.core.http.AsyncStreamResponse
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.Tool
import com.openai.services.async.ChatServiceAsync
import com.openai.services.async.chat.ChatCompletionServiceAsync
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class MasaicStreamingServiceTest {

    private lateinit var toolHandler: MasaicToolHandler
    private lateinit var parameterConverter: MasaicParameterConverter
    private lateinit var toolService: ToolService
    private lateinit var openAIClient: OpenAIClient
    private lateinit var streamingService: MasaicStreamingService

    @BeforeEach
    fun setUp() {
        toolHandler = mockk()
        parameterConverter = mockk()
        toolService = mockk()
        openAIClient = mockk()

        // We’re passing default arguments for allowedMaxToolCalls and maxDuration,
        // but you can override them if you want to test edge cases.
        streamingService = MasaicStreamingService(
            toolHandler = toolHandler,
            parameterConverter = parameterConverter,
            toolService = toolService,
            allowedMaxToolCalls = 3,
            maxDuration = 10_000
        )
    }

    @Test
    fun `test createCompletionStream emits created event initially`() = runTest {
        // Given
        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()
        val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion

        // We mock the client’s streaming call. The simplest approach is to return
        // an object that simulates a subscription with an empty stream or a short stream.
        val mockedSubscription = MockSubscription(emptyList())
        val mockChat = mockk<ChatServiceAsync>()
        val mockCompletions = mockk<ChatCompletionServiceAsync>()

        every { openAIClient.async() } returns mockk {
            every { chat() } returns mockChat
        }
        every { mockChat.completions() } returns mockCompletions
        every { mockCompletions.createStreaming(any()) } returns mockedSubscription

        // When: Collecting the flow
        val resultEvents = streamingService
            .createCompletionStream(openAIClient, params)
            .toList(mutableListOf())

        // Then: We should see an initial CREATED event
        // Check that we have at least one event and that it is the CREATED event
        assertFalse(resultEvents.isEmpty())
        // The very first event is often the 'Created' event
        val firstEvent = resultEvents.first()
        val eventData = firstEvent.data()
        // A simplistic check: eventData might contain "CREATED" or match a known pattern
        assertTrue(eventData?.contains("response.created") == true)
    }

    @Test
    fun `test too many tool calls throws exception`() = runTest {
        // Suppose the user input has more function calls than the allowedMaxToolCalls.
        // We'll simulate that in the input params:
        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()
        val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion
        val inputItems = (1..5).map {
            // each "function call" item
            mockk<ResponseInputItem> {
                every { isFunctionCall() } returns true
            }
        }
        // The input() must be a "Response" for .isResponse() check or mock it as you prefer:
        val mockInput = mockk<ResponseCreateParams.Input> {
            every { isResponse() } returns true
            every { asResponse() } returns inputItems
        }
        every { params.input() } returns mockInput

        // When: We collect the flow, we expect an exception because we exceed the limit (3).
        val flow = streamingService.createCompletionStream(openAIClient, params)

        // Then: Verify exception is thrown
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            flow.toList()
        }
    }

    @Test
    fun `test streaming iteration with a normal chunk`() = runTest {
        val params = mockk<ResponseCreateParams>(relaxed = true)

        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)

        // Fix #1: Make sure these match the property types in your actual code
        // If _parallelToolCalls is JsonField<Boolean>:
        every { params._parallelToolCalls() } returns JsonField.of(false)
        // If _tools is JsonField<List<WhateverToolType>>:
        every { params._tools() } returns JsonField.of<List<Tool>>(emptyList())

        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)
        every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion

        // Build a chunk that is "normal" (no finishReason)
        val chunkChoice = spyk<ChatCompletionChunk.Choice>(ChatCompletionChunk.Choice.builder().index(0).finishReason(
            ChatCompletionChunk.Choice.FinishReason.STOP
        ).delta(
            ChatCompletionChunk.Choice.Delta.builder().content("").build()
        ).logprobs(null).build())
        val chunk = ChatCompletionChunk.builder().choices(listOf(chunkChoice)).id("test_id").created(342342).model("" +
                "gpt-4o").build()

        // We'll pass a single chunk in a list
        val mockedSubscription = MockSubscription(listOf(chunk))
        val mockChat = mockk<ChatServiceAsync>()
        val mockCompletions = mockk<ChatCompletionServiceAsync>()

        every { openAIClient.async() } returns mockk {
            every { chat() } returns mockChat
        }
        every { mockChat.completions() } returns mockCompletions
        every { mockCompletions.createStreaming(any()) } returns mockedSubscription

        val flow = streamingService.createCompletionStream(openAIClient, params)
        val resultEvents = flow.toList(mutableListOf())

        // We expect at least a CREATED event, an IN_PROGRESS event, etc.
        assertTrue(resultEvents.any { it.data()?.contains("response.created") == true })
    }

    // Mock subscription class for demonstration
    // This pretends to be a subscription that your callbackFlow uses.
    private class MockSubscription(
        private val chunks: List<ChatCompletionChunk>
    ) : AsyncStreamResponse<ChatCompletionChunk> {

        private val onComplete = CompletableMockFuture()

        override fun subscribe(handler: AsyncStreamResponse.Handler<ChatCompletionChunk>): AsyncStreamResponse<ChatCompletionChunk> {
            // This is a simplified version of the subscription.
            // We just call the handler with each chunk in the list.
            chunks.forEach { chunk ->
                handler.onNext(chunk)
            }
            // Then we call onComplete() to signal the end of the stream.
            handler.onComplete(Optional.empty())
            return this
        }

        override fun subscribe(
            handler: AsyncStreamResponse.Handler<ChatCompletionChunk>,
            executor: Executor
        ): AsyncStreamResponse<ChatCompletionChunk> {
            return subscribe(handler)
        }

        override fun onCompleteFuture(): CompletableFuture<Void?> {
            return onComplete
        }

        override fun close() {
            // We don’t need to do anything here because we’re not using any resources.
        }

        // This is a minimal mock future for demonstration
        class CompletableMockFuture : java.util.concurrent.CompletableFuture<Void?>() {
            init {
                // complete the future so that `await()` doesn’t block
                complete(null)
            }
        }
    }
}