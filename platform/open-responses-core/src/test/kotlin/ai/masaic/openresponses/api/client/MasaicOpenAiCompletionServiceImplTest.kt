package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.codec.ServerSentEvent
import java.util.stream.Stream
import kotlin.test.assertEquals

/**
 * Unit tests for MasaicOpenAiCompletionServiceImpl#create()
 */
@ExtendWith(MockKExtension::class)
class MasaicOpenAiCompletionServiceImplTest {
    private lateinit var client: OpenAIClient
    private lateinit var toolHandler: MasaicToolHandler
    private lateinit var completionStore: CompletionStore
    private lateinit var telemetryService: TelemetryService
    private lateinit var toolService: ToolService
    private lateinit var service: MasaicOpenAiCompletionServiceImpl

    // Dummy observation for telemetry blocks
    private val mockObservation: Observation = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        client = mockk(relaxed = true)
        toolHandler = mockk(relaxed = true)
        completionStore = mockk(relaxed = true)
        toolService = mockk(relaxed = true)
        // Default alias map empty
        every { toolService.buildAliasMap(any()) } returns emptyMap()

        // Spy real telemetry service, stub its withClientObservation
        val observationRegistry = ObservationRegistry.create()
        val meterRegistry = SimpleMeterRegistry()
        telemetryService = spyk(TelemetryService(observationRegistry, meterRegistry))
        every {
            telemetryService.withClientObservation<ChatCompletion>(any(), any(), any(), any())
        } answers {
            val block = thirdArg<(Observation) -> ChatCompletion>()
            block(mockObservation)
        }

        // Construct service under test
        service =
            spyk(
                MasaicOpenAiCompletionServiceImpl(
                    toolHandler,
                    completionStore,
                    telemetryService,
                    toolService,
                    mockk(), // objectMapper not exercised in these tests
                ),
                recordPrivateCalls = true,
            )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `create without tool calls and store false should return completion and not store`() =
        runBlocking {
            // Given: a ChatCompletion with STOP finish reason (no tool calls)
            val message =
                ChatCompletionMessage
                    .builder()
                    .role(JsonValue.from("assistant"))
                    .content("Hello")
                    .refusal(null)
                    .build()
            val choice =
                ChatCompletion.Choice
                    .builder()
                    .message(message)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .index(0)
                    .logprobs(null)
                    .build()
            val chatCompletion =
                ChatCompletion
                    .builder()
                    .id("test-id")
                    .created(123)
                    .model("gpt-model")
                    .choices(listOf(choice))
                    .build()
            every { telemetryService.withChatCompletionTimer(any(), any(), any<() -> ChatCompletion>()) } returns chatCompletion

            val params =
                ChatCompletionCreateParams
                    .builder()
                    .addUserMessage("Hello")
                    .model(ChatModel.GPT_3_5_TURBO)
                    .build()
            val metadata = InstrumentationMetadataInput()

            // When
            val result = service.create(client, params, metadata)

            // Then: returns the same completion and no storage
            assertEquals(chatCompletion, result)
            verify(exactly = 1) { telemetryService.emitModelInputEvents(any(), params, metadata) }
            verify(exactly = 1) { telemetryService.emitModelOutputEvents(any(), chatCompletion, metadata) }
            verify(exactly = 1) { telemetryService.setChatCompletionObservationAttributes(any(), chatCompletion, params, metadata) }
            coVerify(exactly = 0) { completionStore.storeCompletion(any(), any(), any()) }
        }

    @Test
    fun `create with store true should store completion`() =
        runBlocking {
            // Given: same completion but store flag set
            val message =
                ChatCompletionMessage
                    .builder()
                    .role(JsonValue.from("assistant"))
                    .content("World")
                    .refusal(null)
                    .build()
            val choice =
                ChatCompletion.Choice
                    .builder()
                    .message(message)
                    .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                    .index(0)
                    .logprobs(null)
                    .build()
            val chatCompletion =
                ChatCompletion
                    .builder()
                    .id("store-id")
                    .created(456)
                    .model("gpt-model")
                    .choices(listOf(choice))
                    .build()
            every { telemetryService.withChatCompletionTimer(any(), any(), any<() -> ChatCompletion>()) } returns chatCompletion

            val params =
                ChatCompletionCreateParams
                    .builder()
                    .addUserMessage("World")
                    .model(ChatModel.GPT_3_5_TURBO)
                    .store(true)
                    .build()
            val metadata = InstrumentationMetadataInput()

            coEvery {
                completionStore.storeCompletion(
                    chatCompletion,
                    params.messages(),
                    ofType(CompletionToolRequestContext::class),
                )
            } returns chatCompletion

            // When
            val result = service.create(client, params, metadata)

            // Then: returns and stores once
            assertEquals(chatCompletion, result)
            verify(exactly = 1) { telemetryService.emitModelInputEvents(any(), params, metadata) }
            verify(exactly = 1) { telemetryService.emitModelOutputEvents(any(), chatCompletion, metadata) }
            verify(exactly = 1) { telemetryService.setChatCompletionObservationAttributes(any(), chatCompletion, params, metadata) }
            coVerify(exactly = 1) {
                completionStore.storeCompletion(
                    chatCompletion,
                    params.messages(),
                    ofType(CompletionToolRequestContext::class),
                )
            }
        }

    @Test
    fun `create with non-native tool calls returns original completion without storing`() =
        runBlocking {
            // Given: ChatCompletion requests tool calls
            val toolCall =
                ChatCompletionMessageToolCall
                    .builder()
                    .id("tool-id")
                    .function(
                        ChatCompletionMessageToolCall.Function
                            .builder()
                            .name("unknownTool")
                            .arguments("{}")
                            .build(),
                    ).build()
            // Build assistant message containing tool call
            val assistantMsg =
                ChatCompletionMessage
                    .builder()
                    .role(JsonValue.from("assistant"))
                    .toolCalls(listOf(toolCall))
                    .content("")
                    .refusal(null)
                    .build()
            val choice =
                ChatCompletion.Choice
                    .builder()
                    .message(assistantMsg)
                    .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                    .index(0)
                    .logprobs(null)
                    .build()
            val chatCompletion =
                ChatCompletion
                    .builder()
                    .id("tool-completion-id")
                    .created(1000)
                    .model("gpt-model")
                    .choices(listOf(choice))
                    .build()

            every { telemetryService.withChatCompletionTimer(any(), any(), any<() -> ChatCompletion>()) } returns chatCompletion
            // Stub handler to indicate unresolved client tools
            every {
                toolHandler.handleCompletionToolCall(
                    chatCompletion,
                    any(),
                    client,
                )
            } returns
                CompletionToolCallOutcome.Continue(
                    updatedMessages = emptyList(),
                    hasUnresolvedClientTools = true,
                )

            val params =
                ChatCompletionCreateParams
                    .builder()
                    .addUserMessage("Hi")
                    .model(ChatModel.GPT_3_5_TURBO)
                    .build()
            val metadata = InstrumentationMetadataInput()

            // When
            val result = service.create(client, params, metadata)

            // Then: original completion returned, no store
            assertEquals(chatCompletion, result)
            coVerify(exactly = 0) { completionStore.storeCompletion(any(), any(), any()) }
        }

    @Test
    fun `create with non-native tool calls and store true stores original completion`() =
        runBlocking {
            // Given: same as above but store=true
            val toolCall =
                ChatCompletionMessageToolCall
                    .builder()
                    .id("tool-id")
                    .function(
                        ChatCompletionMessageToolCall.Function
                            .builder()
                            .name("unknownTool")
                            .arguments("{}")
                            .build(),
                    ).build()
            val assistantMsg =
                ChatCompletionMessage
                    .builder()
                    .role(JsonValue.from("assistant"))
                    .toolCalls(listOf(toolCall))
                    .refusal(null)
                    .content("")
                    .build()
            val choice =
                ChatCompletion.Choice
                    .builder()
                    .message(assistantMsg)
                    .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                    .index(0)
                    .logprobs(null)
                    .build()
            val chatCompletion =
                ChatCompletion
                    .builder()
                    .id("tool-store-id")
                    .created(2000)
                    .model("gpt-model")
                    .choices(listOf(choice))
                    .build()
            every { telemetryService.withChatCompletionTimer(any(), any(), any<() -> ChatCompletion>()) } returns chatCompletion
            every {
                toolHandler.handleCompletionToolCall(chatCompletion, any(), client)
            } returns
                CompletionToolCallOutcome.Continue(
                    updatedMessages = emptyList(),
                    hasUnresolvedClientTools = true,
                )
            coEvery {
                completionStore.storeCompletion(chatCompletion, any(), ofType(CompletionToolRequestContext::class))
            } returns chatCompletion

            val params =
                ChatCompletionCreateParams
                    .builder()
                    .addUserMessage("Hi")
                    .model(ChatModel.GPT_3_5_TURBO)
                    .store(true)
                    .build()
            val metadata = InstrumentationMetadataInput()

            // When
            val result = service.create(client, params, metadata)

            // Then: original returned and stored once
            assertEquals(chatCompletion, result)
            coVerify(atLeast = 1) {
                completionStore.storeCompletion(chatCompletion, any(), ofType(CompletionToolRequestContext::class))
            }
        }

    @Test
    fun `createCompletionStream should invoke telemetry observation and return empty flow`() =
        runBlocking {
            // Stub streaming observation
            every {
                telemetryService.withClientObservation<kotlinx.coroutines.flow.Flow<ServerSentEvent<String>>>(
                    "openai.chat.completions.stream",
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val block = thirdArg<(Observation) -> kotlinx.coroutines.flow.Flow<ServerSentEvent<String>>>()
                block(mockObservation)
            }
            // Stub streaming call to return no chunks
            val fakeStream =
                object : com.openai.core.http.StreamResponse<ChatCompletionChunk> {
                    override fun stream(): Stream<ChatCompletionChunk> = emptyList<ChatCompletionChunk>().stream()

                    override fun close() {}
                }
            every { client.chat().completions().createStreaming(any<ChatCompletionCreateParams>()) } returns fakeStream

            val params =
                ChatCompletionCreateParams
                    .builder()
                    .addUserMessage("Hello")
                    .model(ChatModel.GPT_3_5_TURBO)
                    .build()
            val metadata = InstrumentationMetadataInput()

            // When
            val events = service.createCompletionStream(client, params, metadata).toList()

            assertEquals(0, events.size)
        }
}
