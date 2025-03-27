package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.client.MasaicOpenAiResponseServiceImpl
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.ToolChoiceOptions
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
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
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setup() {
        objectMapper = ObjectMapper()
        payloadFormatter =
            mockk {
                every { formatResponse(any()) } answers {
                    objectMapper.valueToTree<JsonNode>(firstArg()) as ObjectNode
                }
            }
        toolService = mockk()
        openAIResponseService = mockk()
        masaicResponseService = MasaicResponseService(openAIResponseService, payloadFormatter, objectMapper)
    }

    @Test
    fun `createResponse should call openAIResponseService and return a Response`() =
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
    fun `getResponse should call the underlying OpenAI client and return a Response`() {
        // Given
        val responseId = "testResponseId"
        val headers: MultiValueMap<String, String> = LinkedMultiValueMap()
        headers.add("Authorization", "Bearer testKey")
        val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()

        // TODO: Mock the OpenAI client and the response
        val expectedResponse = mockk<Response>()
    }

    @Test
    @Disabled("This code is not yet implemented")
    fun `getResponse should throw IllegalArgumentException if Authorization header is missing`() {
        // Given
        val responseId = "testResponseId"
        val headers: MultiValueMap<String, String> = LinkedMultiValueMap() // no "Authorization"
        val queryParams: MultiValueMap<String, String> = LinkedMultiValueMap()

        // When & Then
        assertThrows(ResponseProcessingException::class.java) {
            runBlocking {
                masaicResponseService.getResponse(responseId, headers, queryParams)
            }
        }
    }
}
