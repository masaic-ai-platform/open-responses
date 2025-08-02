package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.core.JsonField
import com.openai.models.ResponsesModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.responses.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import java.util.Optional

class MasaicOpenAiResponseServiceImplTest {
    private lateinit var parameterConverter: MasaicParameterConverter
    private lateinit var toolHandler: MasaicToolHandler
    private lateinit var streamingService: MasaicStreamingService
    private lateinit var responseStore: ResponseStore
    private lateinit var telemetryService: TelemetryService
    private lateinit var serviceImpl: MasaicOpenAiResponseServiceImpl
    private lateinit var toolService: ToolService

    @BeforeEach
    fun setup() {
        parameterConverter = mockk(relaxed = true)
        toolHandler = mockk(relaxed = true)
        streamingService = mockk(relaxed = true)
        responseStore = mockk(relaxed = true)
        toolService = mockk(relaxed = true)

        // Create observation and meter registries
        val observationRegistry = ObservationRegistry.create()
        val meterRegistry = SimpleMeterRegistry()

        // Create telemetry service with real registries
        telemetryService = TelemetryService(observationRegistry, meterRegistry)

        serviceImpl =
            MasaicOpenAiResponseServiceImpl(
                parameterConverter = parameterConverter,
                toolHandler = toolHandler,
                streamingService = streamingService,
                responseStore = responseStore,
                telemetryService = telemetryService,
                toolService = toolService,
            )
    }

    /**
     * Test for recordTokenUsage method
     */
    @Test
    fun `test recordTokenUsage`() {
        // Setup
        val metadata =
            InstrumentationMetadataInput(
                genAISystem = "openai",
                modelProviderAddress = "api.openai.com",
            )
        val completion =
            mockk<ChatCompletion>(relaxed = true) {
                every { model() } returns "gpt-4"
            }
        val params = defaultParamsMock()

        // Act - use telemetryService instead of directly calling recordTokenUsage
        telemetryService.recordTokenUsage(metadata, completion, params, "input", 100L)

        // No assertions needed - the test passes if no exception occurs
    }

    /**
     * createCompletionStream should delegate to streamingService.createCompletionStream
     */
    @Test
    fun `test createCompletionStream`() =
        runBlocking {
            val client = mockk<OpenAIClient>(relaxed = true)
            val params = mockk<ResponseCreateParams>(relaxed = true)
            val flowMock = mockk<Flow<ServerSentEvent<String>>>(relaxed = true)

            every { streamingService.createCompletionStream(client, params, any()) } returns flowMock

            val resultFlow = serviceImpl.createCompletionStream(client, params, InstrumentationMetadataInput())
            assertSame(flowMock, resultFlow)
            verify { streamingService.createCompletionStream(client, params, any()) }
        }

    /**
     * Utility method that returns a partially mocked ResponseCreateParams with minimal needed fields.
     */
    private fun defaultParamsMock(store: Boolean = false): ResponseCreateParams {
        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.of("Say hello to the world")
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ResponsesModel.ofString("gpt-4")
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
        every { params.input() } returns mockInput
        return params
    }
}
