package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import com.openai.client.OpenAIClient
import com.openai.core.JsonField
import com.openai.core.RequestOptions
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.responses.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import java.util.Optional

class MasaicOpenAiResponseServiceImplTest {
    private lateinit var parameterConverter: MasaicParameterConverter
    private lateinit var toolHandler: MasaicToolHandler
    private lateinit var streamingService: MasaicStreamingService
    private lateinit var observationRegistry: ObservationRegistry
    private lateinit var meterRegistry: MeterRegistry
    private lateinit var serviceImpl: MasaicOpenAiResponseServiceImpl

    @BeforeEach
    fun setup() {
        parameterConverter = mockk(relaxed = true)
        toolHandler = mockk(relaxed = true)
        streamingService = mockk(relaxed = true)
        
        // Use real implementations for metrics to avoid complex mocking
        observationRegistry = ObservationRegistry.create()
        meterRegistry = SimpleMeterRegistry()

        serviceImpl =
            MasaicOpenAiResponseServiceImpl(
                parameterConverter = parameterConverter,
                toolHandler = toolHandler,
                streamingService = streamingService,
                observationRegistry = observationRegistry,
                meterRegistry = meterRegistry,
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
     * Test for recordTokenUsage method
     */
    @Test
    fun `test recordTokenUsage`() {
        // Setup
        val metadata =
            CreateResponseMetadataInput(
                genAISystem = "openai",
                modelProviderAddress = "api.openai.com",
            )
        val completion =
            mockk<ChatCompletion>(relaxed = true) {
                every { model() } returns "gpt-4"
            }
        val params = defaultParamsMock()
        
        // Act - we'll just verify it doesn't throw an exception with the real registry
        serviceImpl.recordTokenUsage(metadata, completion, params, "input", 100L)
        
        // No assertions needed - the test passes if no exception occurs
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
     * retrieve(params, requestOptions) -> throws UnsupportedOperationException
     */
    @Test
    fun `test retrieve throws`() {
        val retrieveParams = mockk<ResponseRetrieveParams>(relaxed = true)
        val options = mockk<RequestOptions>(relaxed = true)
        assertThrows(UnsupportedOperationException::class.java) {
            serviceImpl.retrieve(retrieveParams, options)
        }
    }

    /**
     * delete(params, requestOptions) -> throws UnsupportedOperationException
     */
    @Test
    fun `test delete throws`() {
        val deleteParams = mockk<ResponseDeleteParams>(relaxed = true)
        val options = mockk<RequestOptions>(relaxed = true)
        assertThrows(UnsupportedOperationException::class.java) {
            serviceImpl.delete(deleteParams, options)
        }
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
}
