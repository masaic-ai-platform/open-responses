package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.model.CreateResponseRequest
import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.api.model.ResponseInputItemList
import ai.masaic.openresponses.api.service.MasaicResponseService
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import ai.masaic.openresponses.api.utils.PayloadFormatter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.reactive.server.WebTestClient

@ExtendWith(SpringExtension::class)
@Import(TestConfiguration::class)
class ResponseControllerTest {
    private val responseService = mockk<MasaicResponseService>()
    private var webTestClient: WebTestClient
    private var payloadFormatter: PayloadFormatter
    private lateinit var responseStore: ResponseStore
    val objectMapper = jacksonObjectMapper()

    init {
        val responseStore = mockk<ResponseStore>()
        payloadFormatter =
            mockk {
                every { formatResponse(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
                every { formatResponseRequest(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
            }
        val controller = ResponseController(responseService, payloadFormatter, responseStore)
        webTestClient = WebTestClient.bindToController(controller).build()
    }

    @Test
    fun `should create response successfully`() =
        runBlocking {
            // Given
            val request =
                CreateResponseRequest(
                    model = "gpt-4",
                    input = listOf(mapOf("role" to "user", "content" to "Hello, world!")),
                )

            val mockResponse = mockk<Response>(relaxed = true)

            coEvery {
                responseService.createResponse(
                    any(),
                    any(),
                    any(),
                )
            } returns mockResponse

            // When/Then
            webTestClient
                .post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { headers ->
                    headers.add("Content-Type", "application/json")
                }.bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk

            coVerify {
                responseService.createResponse(
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `should retrieve input items for a response successfully`() {
        // Given
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>(relaxed = true)

        // Create actual ResponseInputItem objects instead of mocks
        val inputItems =
            listOf(
                objectMapper.convertValue(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .callId("fc_1")
                            .id("test")
                            .arguments("{}")
                            .name("test")
                            .status(ResponseFunctionToolCall.Status.COMPLETED)
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
            )

        every { responseService.listInputItems(any(), any(), any(), null, null) } returns
            ResponseInputItemList(
                data = inputItems,
                firstId = "test",
                lastId = "test-2",
                hasMore = false,
            )

        // When/Then
        webTestClient
            .get()
            .uri("/v1/responses/$responseId/input_items")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(ResponseInputItemList::class.java)
            .consumeWith {
                assert(it.responseBody?.data?.size == 2)
            }
    }

    @Test
    fun `should return 404 when response not found for input items`() {
        // Given
        val responseId = "nonexistent_resp"

        every { responseService.listInputItems(any(), any(), any(), null, null) } throws ResponseNotFoundException(message = "not found")

        // When/Then
        webTestClient
            .get()
            .uri("/v1/responses/$responseId/input_items")
            .exchange()
            .expectStatus()
            .isNotFound
    }
} 
