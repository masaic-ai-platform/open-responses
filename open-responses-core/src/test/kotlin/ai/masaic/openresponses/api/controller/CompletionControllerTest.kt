package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.client.CompletionStore
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.service.CompletionNotFoundException
import ai.masaic.openresponses.api.service.MasaicCompletionService
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.api.validation.RequestValidator
import com.ninjasquad.springmockk.MockkBean
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.completions.CompletionUsage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.justRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Instant

@WebFluxTest(CompletionController::class)
class CompletionControllerTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockkBean
    private lateinit var masaicCompletionService: MasaicCompletionService

    @MockkBean
    private lateinit var payloadFormatter: PayloadFormatter

    @MockkBean
    private lateinit var completionStore: CompletionStore

    @MockkBean
    private lateinit var requestValidator: RequestValidator

    private lateinit var dummyRequest: CreateCompletionRequest
    private lateinit var dummyCompletion: ChatCompletion

    @BeforeEach
    fun setUp() {
        dummyRequest =
            CreateCompletionRequest(
                model = "gpt-3.5-turbo",
                messages = listOf(mapOf("user" to "Hello")),
                stream = false,
            )

        dummyCompletion =
            ChatCompletion
                .builder()
                .id("cmpl-123")
                .created(Instant.now().epochSecond)
                .model("gpt-3.5-turbo")
                .choices(
                    listOf(
                        ChatCompletion.Choice
                            .builder()
                            .index(0)
                            .message(
                                ChatCompletionMessage
                                    .builder()
                                    .content("hello")
                                    .refusal(null)
                                    .build(),
                            ).finishReason(ChatCompletion.Choice.FinishReason.STOP)
                            .logprobs(null)
                            .build(),
                    ),
                ).usage(
                    CompletionUsage
                        .builder()
                        .completionTokens(1)
                        .promptTokens(3)
                        .totalTokens(4)
                        .build(),
                ).build()

        // Mock the payload formatter by default
        justRun { runBlocking { payloadFormatter.formatCompletionRequest(any()) } }
        justRun { requestValidator.validateCompletionRequest(any()) }
    }

    // --- Test cases will go here ---

    @Test
    fun `createCompletion should return OK for non-streaming request`() {
        // Arrange
        val request = dummyRequest.copy(stream = false)
        coEvery {
            masaicCompletionService.createCompletion(request, any(), any())
        } returns dummyCompletion

        // Act & Assert
        webTestClient
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(request))
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON)
            .expectBody(ChatCompletion::class.java)

        coVerify { payloadFormatter.formatCompletionRequest(request) }
        coVerify { masaicCompletionService.createCompletion(request, any(), any()) }
    }

    @Test
    fun `createCompletion should return event stream for streaming request`() {
        // Arrange
        val request = dummyRequest.copy(stream = true)
        val dummyFlow: Flow<ServerSentEvent<String>> = flowOf(ServerSentEvent.builder<String>().data("event 1").build(), ServerSentEvent.builder<String>().data("event 2").build())
        coEvery {
            masaicCompletionService.createStreamingCompletion(request, any(), any())
        } returns dummyFlow

        // Act & Assert
        webTestClient
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(request))
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
        // Add further assertions for the stream content if necessary
        // .expectBody(String::class.java) // Or Flux<ServerSentEvent<String>>

        coVerify { payloadFormatter.formatCompletionRequest(request) }
        coVerify { masaicCompletionService.createStreamingCompletion(request, any(), any()) }
    }

    @Test
    fun `getCompletion should return OK when completion exists`() {
        // Arrange
        val completionId = "cmpl-123"
        coEvery { masaicCompletionService.getCompletion(completionId) } returns dummyCompletion

        // Act & Assert
        webTestClient
            .get()
            .uri("/v1/chat/completions/{completionId}", completionId)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON)
            .expectBody(ChatCompletion::class.java)

        coVerify { masaicCompletionService.getCompletion(completionId) }
    }

    @Test
    fun `getCompletion should return Not Found when completion does not exist`() {
        // Arrange
        val completionId = "cmpl-notfound"
        val exceptionMessage = "Completion with ID $completionId not found"
        coEvery { masaicCompletionService.getCompletion(completionId) } throws CompletionNotFoundException(exceptionMessage)

        // Act & Assert
        webTestClient
            .get()
            .uri("/v1/chat/completions/{completionId}", completionId)
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
        coVerify { masaicCompletionService.getCompletion(completionId) }
    }

    @Test
    fun `deleteCompletion should return OK with deletion status`() {
        // Arrange
        val completionId = "cmpl-todelete"
        coEvery { completionStore.deleteCompletion(completionId) } returns true

        // Act & Assert
        webTestClient
            .delete()
            .uri("/v1/chat/completions/{completionId}", completionId)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo(completionId)
            .jsonPath("$.deleted")
            .isEqualTo(true)
            .jsonPath("$.object")
            .isEqualTo("chat.completion")

        coVerify { completionStore.deleteCompletion(completionId) }
    }
} 
