package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.CreateResponseRequest
import ai.masaic.openresponses.api.service.MasaicResponseService
import ai.masaic.openresponses.api.utils.PayloadFormatter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openai.models.responses.Response
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
    private lateinit var payloadFormatter: PayloadFormatter

    init {
        val objectMapper = ObjectMapper()
        payloadFormatter =
            mockk {
                every { formatResponse(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
                every { formatResponseRequest(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
            }
        val controller = ResponseController(responseService, payloadFormatter)
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
} 
