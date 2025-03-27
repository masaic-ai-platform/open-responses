package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.client.MasaicOpenAiResponseServiceImpl
import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.ToolChoiceOptions
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.util.Optional

@ExtendWith(SpringExtension::class)
class MasaicResponseServiceTest {
    private lateinit var toolService: ToolService
    private lateinit var openAIResponseService: MasaicOpenAiResponseServiceImpl
    private lateinit var masaicResponseService: MasaicResponseService
    private lateinit var payloadFormatter: PayloadFormatter
    private lateinit var responseStore: ResponseStore
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setup() {
        responseStore = mockk()
        objectMapper = jacksonObjectMapper()
        payloadFormatter =
            mockk {
                every { formatResponse(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
            }
        toolService = mockk()
        openAIResponseService = mockk()
        masaicResponseService = MasaicResponseService(openAIResponseService, responseStore, payloadFormatter, objectMapper)
    }

    @Test
    fun `createResponse should call openAIResponseService and return a Response`() =
        runBlocking {
            // Given
            val request =
                mockk<ResponseCreateParams.Body> {
                    every { previousResponseId() } returns Optional.empty()
                    every { input() } returns ResponseCreateParams.Input.ofText("Test")
                    every { model() } returns ChatModel.of("gpt-4o")
                    every { instructions() } returns Optional.empty()
                    every { reasoning() } returns Optional.empty()
                    every { parallelToolCalls() } returns Optional.of(true)
                    every { maxOutputTokens() } returns Optional.of(256)
                    every { include() } returns Optional.empty()
                    every { metadata() } returns Optional.empty()
                    every { store() } returns Optional.of(true)
                    every { temperature() } returns Optional.of(0.7)
                    every { topP() } returns Optional.of(0.9)
                    every { truncation() } returns Optional.empty()
                    every { _additionalProperties() } returns emptyMap()

                    // For the optional fields that return java.util.Optional<T>:
                    every { text() } returns Optional.of(ResponseTextConfig.builder().build())
                    every { user() } returns Optional.of("someUser")
                    every { toolChoice() } returns Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                    every { tools() } returns Optional.of(listOf())
                }
            val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
            headers.add("Authorization", "Bearer testKey")
            val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()

            val expectedResponse = mockk<Response>()
            every {
                openAIResponseService.create(ofType<OpenAIClient>(), any())
            } returns expectedResponse

            // When
            val result = masaicResponseService.createResponse(request, headers, queryParams)

            // Then
            assertSame(expectedResponse, result, "Should return the mocked response")
            verify(exactly = 1) {
                openAIResponseService.create(ofType<OpenAIClient>(), any())
            }
            confirmVerified(openAIResponseService)
        }

    @Test
    fun `createResponse should throw IllegalArgumentException if Authorization header is missing`() =
        runBlocking {
            // Given
            val request =
                mockk<ResponseCreateParams.Body> {
                    every { input() } returns ResponseCreateParams.Input.ofText("Test")
                    every { model() } returns ChatModel.of("gpt-4o")
                    every { instructions() } returns Optional.empty()
                    every { reasoning() } returns Optional.empty()
                    every { parallelToolCalls() } returns Optional.of(true)
                    every { maxOutputTokens() } returns Optional.of(256)
                    every { include() } returns Optional.empty()
                    every { metadata() } returns Optional.empty()
                    every { store() } returns Optional.of(true)
                    every { temperature() } returns Optional.of(0.7)
                    every { topP() } returns Optional.of(0.9)
                    every { truncation() } returns Optional.empty()
                    every { _additionalProperties() } returns emptyMap()

                    // For the optional fields that return java.util.Optional<T>:
                    every { text() } returns Optional.of(ResponseTextConfig.builder().build())
                    every { user() } returns Optional.of("someUser")
                    every { toolChoice() } returns Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                    every { tools() } returns Optional.of(listOf())
                }
            val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
            val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()

            // When & Then
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    masaicResponseService.createResponse(request, headers, queryParams)
                }
            }
            Unit
        }

    @Test
    fun `createStreamingResponse should return a Flow of ServerSentEvent`() =
        runBlocking {
            // Given
            val request =
                mockk<ResponseCreateParams.Body> {
                    every { previousResponseId() } returns Optional.empty()
                    every { input() } returns ResponseCreateParams.Input.ofText("Test")
                    every { model() } returns ChatModel.of("gpt-4o")
                    every { instructions() } returns Optional.empty()
                    every { reasoning() } returns Optional.empty()
                    every { parallelToolCalls() } returns Optional.of(true)
                    every { maxOutputTokens() } returns Optional.of(256)
                    every { include() } returns Optional.empty()
                    every { metadata() } returns Optional.empty()
                    every { store() } returns Optional.of(true)
                    every { temperature() } returns Optional.of(0.7)
                    every { topP() } returns Optional.of(0.9)
                    every { truncation() } returns Optional.empty()
                    every { _additionalProperties() } returns emptyMap()

                    // For the optional fields that return java.util.Optional<T>:
                    every { text() } returns Optional.of(ResponseTextConfig.builder().build())
                    every { user() } returns Optional.of("someUser")
                    every { toolChoice() } returns Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                    every { tools() } returns Optional.of(listOf())
                }
            val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
            headers.add("Authorization", "Bearer testKey")
            val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()

            val expectedFlow: Flow<ServerSentEvent<String>> =
                flowOf(
                    ServerSentEvent.builder("data1").build(),
                    ServerSentEvent.builder("data2").build(),
                )

            every {
                openAIResponseService.createCompletionStream(any(), ofType())
            } returns expectedFlow

            // When
            val resultFlow = masaicResponseService.createStreamingResponse(request, headers, queryParams)
            val collectedEvents = resultFlow.toList()

            // Then
            assertEquals(2, collectedEvents.size)
            assertEquals("data1", collectedEvents[0].data())
            assertEquals("data2", collectedEvents[1].data())

            verify(exactly = 1) {
                openAIResponseService.createCompletionStream(any(), any())
            }
            confirmVerified(openAIResponseService)
        }

    @Test
    fun `createStreamingResponse should throw IllegalArgumentException if Authorization header is missing`() {
        // Given
        val request = mockk<ResponseCreateParams.Body>()
        val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
        val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()

        // When & Then
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                masaicResponseService.createStreamingResponse(request, headers, queryParams)
            }
        }
    }

    @Test
    fun `listInputItems should retrieve input items from ResponseStore`() {
        // Given
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>()

        // Create actual ResponseInputItem objects instead of mocks
        val inputItems =
            listOf(
                objectMapper.convertValue(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .id("fc_1")
                            .name("test_function")
                            .arguments("{}")
                            .callId("fc_1")
                            .build(),
                    ),
                    InputMessageItem::class.java,
                ),
                objectMapper.convertValue(
                    ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput
                            .builder()
                            .callId("fc_1")
                            .output("{\"result\": \"success\"}")
                            .build(),
                    ),
                    InputMessageItem::class.java,
                ),
                objectMapper.convertValue(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message
                            .builder()
                            .role(ResponseInputItem.Message.Role.USER)
                            .content(
                                listOf(
                                    ResponseInputContent.ofInputText(
                                        ResponseInputText
                                            .builder()
                                            .text("Hello")
                                            .build(),
                                    ),
                                ),
                            ).build(),
                    ),
                    InputMessageItem::class.java,
                ),
            )

        every { responseStore.getResponse(responseId) } returns mockResponse
        every { responseStore.getInputItems(responseId) } returns inputItems

        // When
        val result = masaicResponseService.listInputItems(responseId, 2, "desc", null, null)

        // Then
        assertEquals(2, result.data.size, "Should return limited input items")
        verify(exactly = 1) { responseStore.getResponse(responseId) }
        verify(exactly = 1) { responseStore.getInputItems(responseId) }
    }

    @Test
    fun `listInputItems should return all items if limit is greater than available items`() {
        // Given
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>()

        // Create actual ResponseInputItem objects instead of mocks
        val inputItems =
            listOf(
                objectMapper.convertValue(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .id("fc_1")
                            .name("test_function")
                            .arguments("{}")
                            .callId("fc_1")
                            .build(),
                    ),
                    InputMessageItem::class.java,
                ),
                objectMapper.convertValue(
                    ResponseInputItem.ofMessage(
                        ResponseInputItem.Message
                            .builder()
                            .role(ResponseInputItem.Message.Role.USER)
                            .content(
                                listOf(
                                    ResponseInputContent.ofInputText(
                                        ResponseInputText
                                            .builder()
                                            .text("Hello")
                                            .build(),
                                    ),
                                ),
                            ).build(),
                    ),
                    InputMessageItem::class.java,
                ),
            )

        every { responseStore.getResponse(responseId) } returns mockResponse
        every { responseStore.getInputItems(responseId) } returns inputItems

        // When
        val result = masaicResponseService.listInputItems(responseId, 5, "desc", null, null)

        // Then
        assertEquals(2, result.data.size, "Should return all input items")
        verify(exactly = 1) { responseStore.getResponse(responseId) }
        verify(exactly = 1) { responseStore.getInputItems(responseId) }
    }

    @Test
    fun `listInputItems should throw ResponseNotFoundException if response not found`() {
        // Given
        val responseId = "nonexistent_resp"
        every { responseStore.getResponse(responseId) } returns null

        // When & Then
        assertThrows(ResponseNotFoundException::class.java) {
            masaicResponseService.listInputItems(responseId, 10, "desc", null, null)
        }

        verify(exactly = 1) { responseStore.getResponse(responseId) }
        verify(exactly = 0) { responseStore.getInputItems(any()) }
    }
}
