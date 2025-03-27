package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openai.client.OpenAIClient
import com.openai.core.JsonField
import com.openai.core.JsonValue
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class MasaicStreamingServiceTest {
    private lateinit var toolHandler: MasaicToolHandler
    private lateinit var parameterConverter: MasaicParameterConverter
    private lateinit var toolService: ToolService
    private lateinit var openAIClient: OpenAIClient
    private lateinit var streamingService: MasaicStreamingService
    private lateinit var payloadFormatter: PayloadFormatter
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        toolHandler = mockk()
        parameterConverter = mockk()
        toolService = mockk()
        openAIClient = mockk()
        objectMapper = ObjectMapper()
        payloadFormatter =
            mockk {
                every { formatResponseStreamEvent(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
            }

        streamingService =
            MasaicStreamingService(
                toolHandler = toolHandler,
                parameterConverter = parameterConverter,
                toolService = toolService,
                allowedMaxToolCalls = 3, // test limit
                maxDuration = 10_000, // test duration,
                payloadFormatter,
                objectMapper,
            )
    }

    /**
     *    Tests that we emit a CREATED event immediately when the stream starts.
     *    This indirectly tests 'emitCreatedEventIfNeeded' and 'buildInitialResponseItems'.
     */
    @Test
    fun `test createCompletionStream emits CREATED event initially`() =
        runTest {
            // Given
            val params = defaultParamsMock()
            val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)
            every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion

            // Mock the client’s streaming call -> empty stream
            val mockedSubscription = MockSubscription(emptyList())
            val mockChat = mockk<ChatServiceAsync>()
            val mockCompletions = mockk<ChatCompletionServiceAsync>()

            every { openAIClient.async() } returns
                mockk {
                    every { chat() } returns mockChat
                }
            every { mockChat.completions() } returns mockCompletions
            every { mockCompletions.createStreaming(any()) } returns mockedSubscription

            // When
            val resultEvents =
                streamingService
                    .createCompletionStream(openAIClient, params)
                    .toList(mutableListOf())

            // Then
            assertFalse(resultEvents.isEmpty())
            val firstEvent = resultEvents.first()
            // The data may be a JSON containing a 'CREATED' or 'response.created' indicator
            assertTrue(firstEvent.data()?.contains("response.created") == true)
        }

    /**
     *    Tests that 'tooManyToolCalls' blocks the flow if we have more function calls
     *    than allowedMaxToolCalls. Indirectly tests 'tooManyToolCalls' and 'emitTooManyToolCallsError'.
     */
    @Test
    fun `test too many tool calls stops stream`() =
        runTest {
            // Suppose the user input has 5 function calls, exceeding our limit of 3
            val params = defaultParamsMock()
            val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)
            every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion

            val inputItems =
                (1..5).map {
                    mockk<ResponseInputItem> {
                        every { isFunctionCall() } returns true
                    }
                }
            // The input must be a Response
            val mockInput =
                mockk<ResponseCreateParams.Input> {
                    every { isResponse() } returns true
                    every { asResponse() } returns inputItems
                }
            every { params.input() } returns mockInput

            // When we try to collect the flow, an error event is produced and an exception is thrown
            val flow = streamingService.createCompletionStream(openAIClient, params)

            // Then
            assertThrows<UnsupportedOperationException> {
                flow.toList()
            }
        }

    /**
     *    Tests a normal streaming chunk scenario, verifying that we get an IN_PROGRESS event
     *    (which indirectly tests 'executeStreamingIteration' and text handling).
     */
    @Test
    fun `test streaming iteration with normal chunk`() =
        runTest {
            val params = defaultParamsMock()
            val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)
            every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion

            // Provide a chunk that has a 'stop' finish reason to finalize quickly
            val chunkChoice =
                ChatCompletionChunk.Choice
                    .builder()
                    .index(0)
                    .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                    .delta(
                        ChatCompletionChunk.Choice.Delta
                            .builder()
                            .content("Hello from AI")
                            .build(),
                    ).build()
            val chunk =
                ChatCompletionChunk
                    .builder()
                    .choices(listOf(chunkChoice))
                    .id("test_id")
                    .created(123456)
                    .model("gpt-4")
                    .build()

            val mockedSubscription = MockSubscription(listOf(chunk))
            val mockChat = mockk<ChatServiceAsync>()
            val mockCompletions = mockk<ChatCompletionServiceAsync>()

            every { openAIClient.async() } returns
                mockk {
                    every { chat() } returns mockChat
                }
            every { mockChat.completions() } returns mockCompletions
            every { mockCompletions.createStreaming(any()) } returns mockedSubscription

            // When
            val flow = streamingService.createCompletionStream(openAIClient, params)
            val events = flow.toList(mutableListOf())

            // Then
            // Expect to see at least one 'IN_PROGRESS' event (some substring or marker) from the chunk
            assertTrue(events.any { it.data()?.contains("response.in_progress") == true })
            // And a COMPLETED event for the STOP
            assertTrue(events.any { it.data()?.contains("response.completed") == true })
        }

    /**
     *    Tests the scenario of a tool call chunk (finishReason == \"tool_calls\"), ensuring we do
     *    an additional iteration. This indirectly tests function call handling in 'executeStreamingIteration'.
     */
    @Test
    fun `test streaming iteration triggers tool call iteration`() =
        runTest {
            // 1) Mock the *original* params object.
            //    We disable relaxed mode so that MockK will throw if we forget a stub.
            val originalParams = mockk<ResponseCreateParams>(relaxed = true)

            // Provide stubs for everything the code might call on 'originalParams'.
            every { originalParams.instructions() } returns Optional.of("My instructions")
            every { originalParams.metadata() } returns Optional.empty()
            every { originalParams.previousResponseId() } returns Optional.empty()
            every { originalParams.model() } returns ChatModel.of("gpt-4")
            every { originalParams.temperature() } returns Optional.of(0.7)
            every { originalParams._parallelToolCalls() } returns JsonValue.from(false)
            every { originalParams._tools() } returns JsonValue.from(listOf<Tool>())
            every { originalParams.topP() } returns Optional.of(1.0)
            every { originalParams.maxOutputTokens() } returns Optional.of(512)
            every { originalParams.reasoning() } returns Optional.empty()
            every { originalParams.toolChoice() } returns Optional.empty()
            val initialInput = mockk<ResponseCreateParams.Input>(relaxed = false)
            every { initialInput.isResponse() } returns false
            every { initialInput.isText() } returns true
            every { initialInput.asText() } returns "User's original text"
            every { originalParams.input() } returns initialInput

            val secondParams = mockk<ResponseCreateParams>(relaxed = true)
            every { secondParams.instructions() } returns Optional.of("My instructions")
            every { secondParams.metadata() } returns Optional.empty()
            every { secondParams.previousResponseId() } returns Optional.empty()
            every { secondParams.model() } returns ChatModel.of("gpt-4")
            every { secondParams.temperature() } returns Optional.of(0.7)
            every { secondParams.topP() } returns Optional.of(1.0)
            every { secondParams.maxOutputTokens() } returns Optional.of(512)
            every { secondParams.reasoning() } returns Optional.empty()
            every { secondParams.toolChoice() } returns Optional.empty()
            every { secondParams._parallelToolCalls() } returns JsonValue.from(false)
            val mockBuilder = mockk<ResponseCreateParams.Builder>(relaxed = false)
            every { mockBuilder.input(ofType<ResponseCreateParams.Input>()) } returns mockBuilder
            every { mockBuilder.build() } returns secondParams

            // Tie it all together:
            every { originalParams.toBuilder() } returns mockBuilder

            // 4) Prepare the streaming logic for the first chunk that triggers 'tool_calls',
            //    and the second chunk that might finalize or proceed differently.
            // Provide a chunk that signals \"tool_calls\" in finishReason
            val chunkChoice =
                ChatCompletionChunk.Choice
                    .builder()
                    .index(0)
                    .finishReason(ChatCompletionChunk.Choice.FinishReason.of("tool_calls"))
                    .delta(
                        ChatCompletionChunk.Choice.Delta
                            .builder()
                            .toolCalls(
                                listOf(
                                    ChatCompletionChunk.Choice.Delta.ToolCall
                                        .builder()
                                        .id("tool-1")
                                        .index(0)
                                        .function(
                                            ChatCompletionChunk.Choice.Delta.ToolCall.Function
                                                .builder()
                                                .arguments("{}")
                                                .name("my_function")
                                                .build(),
                                        ).type(ChatCompletionChunk.Choice.Delta.ToolCall.Type.FUNCTION)
                                        .build(),
                                ),
                            ).build(),
                    ).build()

            val chunk =
                ChatCompletionChunk
                    .builder()
                    .choices(listOf(chunkChoice))
                    .id("tool_call_chunk")
                    .created(123)
                    .model("gpt-4")
                    .build()

            // We'll do two subscriptions: the first returns the tool_call chunk,
            // the second returns a normal STOP (or tool_calls again) chunk
            val chunkChoice2 =
                ChatCompletionChunk.Choice
                    .builder()
                    .index(0)
                    // Let's pretend it STOPs now:
                    .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                    .delta(
                        ChatCompletionChunk.Choice.Delta
                            .builder()
                            .content("Tool call done")
                            .build(),
                    ).build()

            val chunk2 =
                ChatCompletionChunk
                    .builder()
                    .choices(listOf(chunkChoice2))
                    .created(13123123)
                    .model("gpt-4o")
                    .id("second_call")
                    .build()

            val firstSub = MockSubscription(listOf(chunk))
            val secondSub = MockSubscription(listOf(chunk2))

            // 5) We track how many times createStreaming(...) is invoked,
            //    so we know the second iteration actually occurred.
            var subscriptionCount = 0
            val mockChat = mockk<ChatServiceAsync>(relaxed = false)
            val mockCompletions = mockk<ChatCompletionServiceAsync>(relaxed = false)
            val mockedPreparedCompletion = mockk<ChatCompletionCreateParams>(relaxed = true)

            // The parameterConverter returns a prepared completion for any iteration:
            every { parameterConverter.prepareCompletion(any()) } returns mockedPreparedCompletion

            // We'll say toolService recognizes \"my_function\"
            every { toolService.getFunctionTool("my_function") } returns FunctionTool(name = "my_function")

            // Now stub the openAIClient usage
            every { openAIClient.async() } returns
                mockk(relaxed = false) {
                    every { chat() } returns mockChat
                }
            every { mockChat.completions() } returns mockCompletions
            // Each time createStreaming(...) is called, we return a different subscription
            every { mockCompletions.createStreaming(any()) } answers {
                subscriptionCount += 1
                if (subscriptionCount == 1) firstSub else secondSub
            }

            // The toolHandler will produce new input items for the second iteration
            val toolHandlerItems =
                listOf(
                    mockk<ResponseInputItem>(relaxed = false).apply {
                        every { isEasyInputMessage() } returns false
                        every { isResponseOutputMessage() } returns true
                    },
                )
            every { toolHandler.handleMasaicToolCall(ofType<ResponseCreateParams>(), ofType()) } returns toolHandlerItems

            // 6) Now run the flow. This should trigger TWO iterations:
            val events =
                streamingService
                    .createCompletionStream(openAIClient, originalParams)
                    .toList(mutableListOf())

            // 7) Verify the result. We expect 2 subscriptions -> 2 iterations.
            assertEquals(2, subscriptionCount)

            // We also expect a final COMPLETED or STOP event at the end
            assertTrue(
                events.any { sse -> sse.data()?.contains("response.completed") == true } ||
                    events.any { sse -> sse.data()?.contains("response.incomplete") == true },
            )
        }

    /**
     * Utility method that returns a partially mocked ResponseCreateParams with minimal needed fields.
     */
    private fun defaultParamsMock(): ResponseCreateParams {
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
        // By default, create a text-based input
        val mockInput =
            mockk<ResponseCreateParams.Input> {
                every { isResponse() } returns false
                every { isText() } returns true
                every { asText() } returns "Hello world"
            }
        every { params.input() } returns mockInput
        return params
    }

    /**
     * Simple MockSubscription that fires all chunks immediately.
     */
    private class MockSubscription(
        private val chunks: List<ChatCompletionChunk>,
    ) : AsyncStreamResponse<ChatCompletionChunk> {
        private val onComplete = CompletableMockFuture()

        override fun subscribe(handler: AsyncStreamResponse.Handler<ChatCompletionChunk>): AsyncStreamResponse<ChatCompletionChunk> {
            chunks.forEach { handler.onNext(it) }
            handler.onComplete(Optional.empty())
            return this
        }

        override fun subscribe(
            handler: AsyncStreamResponse.Handler<ChatCompletionChunk>,
            executor: Executor,
        ): AsyncStreamResponse<ChatCompletionChunk> = subscribe(handler)

        override fun onCompleteFuture(): CompletableFuture<Void?> = onComplete

        override fun close() {}

        class CompletableMockFuture : CompletableFuture<Void?>() {
            init {
                complete(null)
            }
        }
    }
}
