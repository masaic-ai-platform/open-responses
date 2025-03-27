package ai.masaic.openresponses.api.utils

import ai.masaic.openresponses.api.client.toResponse
import ai.masaic.openresponses.api.model.CreateResponseRequest
import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.api.model.MasaicManagedTool
import ai.masaic.openresponses.api.model.Tool
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.core.JsonField
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.completions.CompletionUsage
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCompletedEvent
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseCreatedEvent
import com.openai.models.responses.ResponseFailedEvent
import com.openai.models.responses.ResponseInProgressEvent
import com.openai.models.responses.ResponseStreamEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.Optional
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PayloadFormatterTest {
    private lateinit var toolService: ToolService
    private lateinit var mapper: ObjectMapper
    private lateinit var payloadFormatter: PayloadFormatter

    @BeforeEach
    fun setup() {
        toolService = mockk()
        mapper = ObjectMapper()
        payloadFormatter = PayloadFormatter(toolService, mapper)
    }

    @Nested
    inner class FormatResponseRequestTests {
        @Test
        fun `formatResponseRequest - with no tools does nothing`() {
            val request = CreateResponseRequest(tools = null, input = mockk(), model = "gpt-4o")

            payloadFormatter.formatResponseRequest(request)

            // Tools remain null, no changes
            assertNull(request.tools)
        }

        @Test
        fun `formatResponseRequest - with non-MasaicManagedTool remains unchanged`() {
            val someTool: Tool = mockk()
            val request = CreateResponseRequest(tools = listOf(someTool), input = mockk(), model = "gpt-4o")

            payloadFormatter.formatResponseRequest(request)

            // The original tool stays the same
            Assertions.assertAll(
                { Assertions.assertNotNull(request.tools) },
                { Assertions.assertEquals(1, request.tools!!.size) },
                { Assertions.assertSame(someTool, request.tools!![0]) },
            )
        }

        @Test
        fun `formatResponseRequest - with MasaicManagedTool that exists in tool service`() {
            val masaicTool = MasaicManagedTool(type = "myManagedTool")
            val replacedTool: FunctionTool = mockk()

            // Mock the tool service to return a valid tool for "myManagedTool"
            every { toolService.getFunctionTool("myManagedTool") } returns replacedTool

            val request = CreateResponseRequest(tools = listOf(masaicTool), input = mockk(), model = "gpt-4o")

            payloadFormatter.formatResponseRequest(request)

            // The MasaicManagedTool should be replaced with the result from toolService
            Assertions.assertAll(
                { Assertions.assertNotNull(request.tools) },
                { Assertions.assertEquals(1, request.tools!!.size) },
                { Assertions.assertSame(replacedTool, request.tools!![0]) },
            )

            // Verify the mock was called
            verify(exactly = 1) { toolService.getFunctionTool("myManagedTool") }
        }

        @Test
        fun `formatResponseRequest - with MasaicManagedTool not found in tool service throws BAD_REQUEST`() {
            val masaicTool = MasaicManagedTool(type = "unknownTool")
            every { toolService.getFunctionTool("unknownTool") } returns null

            val request = CreateResponseRequest(tools = listOf(masaicTool), input = mockk(), model = "gpt-4o")

            assertFailsWith<ResponseStatusException> {
                payloadFormatter.formatResponseRequest(request)
            }
        }
    }

    @Nested
    inner class FormatResponseTests {
        @Test
        fun `formatResponse - transforms function tools and created_at field`() {
            // Create a sample Response with a "function" tool
            val created = System.currentTimeMillis()
            val response: Response = getMockResponse(created, true)

            // Mock the tool service so that "testFunction" is known
            every { toolService.getAvailableTool("myTool") } returns mockk(relaxed = true)

            val resultNode: JsonNode = payloadFormatter.formatResponse(response)

            // Verify the tools array was replaced
            val toolsNode = resultNode.get("tools")
            Assertions.assertTrue(toolsNode.isArray)
            Assertions.assertEquals("myTool", toolsNode[0].get("type").asText())

            // Verify created_at was converted to BigDecimal
            val createdAtNode = resultNode.get("created_at")
            Assertions.assertTrue(createdAtNode.isBigDecimal)
            Assertions.assertEquals(
                0,
                BigDecimal.valueOf(created).compareTo(createdAtNode.decimalValue()),
            )
        }

        @Test
        fun `formatResponse - no tools means no transformation on tools array`() {
            val created = System.currentTimeMillis()
            val response: Response = getMockResponse(created)

            // No expectations on toolService since there are no tools to transform
            val resultNode: JsonNode = payloadFormatter.formatResponse(response)

            // tools is null, so there's no array
            Assertions.assertTrue(resultNode.get("tools").size() == 0)

            // Verify created_at was converted
            val createdAtNode = resultNode.get("created_at")
            Assertions.assertTrue(createdAtNode.isBigDecimal)
            Assertions.assertEquals(
                0,
                BigDecimal.valueOf(created).compareTo(createdAtNode.decimalValue()),
            )
        }

        fun getMockResponse(
            createdAt: Long,
            includeTools: Boolean = false,
        ): Response {
            // Arrange
            val chatCompletion = mockk<ChatCompletion>()
            val params = mockk<ResponseCreateParams>()

            // Minimal setup to avoid exceptions
            every { chatCompletion.id() } returns "ext-id"
            every { chatCompletion.created() } returns createdAt
            every { chatCompletion.model() } returns "gpt-4"
            every { chatCompletion.choices() } returns emptyList()
            every { chatCompletion.usage() } returns
                Optional.of(
                    CompletionUsage
                        .builder()
                        .completionTokens(3)
                        .totalTokens(8)
                        .promptTokens(5)
                        .build(),
                )

            every { params.instructions() } returns Optional.empty()
            every { params.metadata() } returns Optional.empty()
            every { params.model() } returns ChatModel.of("gpt-4")
            every { params.temperature() } returns Optional.of(0.7)
            every { params._parallelToolCalls() } returns JsonField.of(false)
            if (includeTools) {
                every { params._tools() } returns
                    JsonField.of(
                        listOf(
                            com.openai.models.responses.Tool.ofFunction(
                                com.openai.models.responses.FunctionTool
                                    .builder()
                                    .type(JsonValue.from("myTool"))
                                    .name("myTool")
                                    .strict(true)
                                    .parameters(
                                        com.openai.models.responses.FunctionTool.Parameters
                                            .builder()
                                            .build(),
                                    ).build(),
                            ),
                        ),
                    )
            } else {
                every { params._tools() } returns JsonField.of(listOf())
            }
            every { params.toolChoice() } returns Optional.empty()
            every { params.topP() } returns Optional.of(1.0)
            every { params.maxOutputTokens() } returns Optional.of(512)
            every { params.previousResponseId() } returns Optional.empty()
            every { params.reasoning() } returns Optional.empty()

            // Act
            return chatCompletion.toResponse(params)
        }
    }

    @Nested
    inner class FormatResponseStreamEventTests {
        @Test
        fun `formatResponseStreamEvent - completed event`() {
            val created = System.currentTimeMillis()
            val response: Response = getMockResponse(created)
            val event: ResponseStreamEvent =
                ResponseStreamEvent.ofCompleted(
                    ResponseCompletedEvent
                        .builder()
                        .response(
                            response,
                        ).build(),
                )

            val node = payloadFormatter.formatResponseStreamEvent(event)

            // Check created_at is converted
            val createdAt = node["response"]["created_at"]
            Assertions.assertNotNull(createdAt)
            Assertions.assertTrue(createdAt.isBigDecimal)
            Assertions.assertEquals(
                0,
                BigDecimal.valueOf(created).compareTo(createdAt.decimalValue()),
            )
        }

        @Test
        fun `formatResponseStreamEvent - created event`() {
            val created = System.currentTimeMillis()
            val response: Response = getMockResponse(created)
            val event: ResponseStreamEvent =
                ResponseStreamEvent.ofCreated(
                    ResponseCreatedEvent
                        .builder()
                        .response(
                            response,
                        ).build(),
                )

            val node = payloadFormatter.formatResponseStreamEvent(event)

            val createdAt = node["response"]["created_at"]
            Assertions.assertTrue(createdAt.isBigDecimal)
            Assertions.assertEquals(
                0,
                BigDecimal.valueOf(created).compareTo(createdAt.decimalValue()),
            )
        }

        @Test
        fun `formatResponseStreamEvent - inProgress event updates tools`() {
            val created = System.currentTimeMillis()
            val response: Response = getMockResponse(created)
            every { toolService.getAvailableTool("myTool") } returns mockk(relaxed = true)

            val event: ResponseStreamEvent =
                ResponseStreamEvent.ofInProgress(
                    ResponseInProgressEvent
                        .builder()
                        .response(
                            response,
                        ).build(),
                )

            val node = payloadFormatter.formatResponseStreamEvent(event)

            // Tools array should have had the "function" replaced with { "type": "myTool" }
            val toolType = node["response"]["tools"][0].get("type").asText()
            Assertions.assertEquals("myTool", toolType)

            // created_at is BigDecimal
            Assertions.assertTrue(node["response"]["created_at"].isBigDecimal)
            Assertions.assertEquals(
                0,
                BigDecimal.valueOf(created).compareTo(node["response"]["created_at"].decimalValue()),
            )
        }

        @Test
        fun `formatResponseStreamEvent - failed event`() {
            val created = System.currentTimeMillis()
            val response: Response = getMockResponse(created)
            val event: ResponseStreamEvent =
                ResponseStreamEvent.ofFailed(
                    ResponseFailedEvent
                        .builder()
                        .response(
                            response,
                        ).build(),
                )

            val node = payloadFormatter.formatResponseStreamEvent(event)
            val createdAt = node["response"]["created_at"]
            Assertions.assertTrue(createdAt.isBigDecimal)
            Assertions.assertEquals(
                0,
                BigDecimal.valueOf(created).compareTo(createdAt.decimalValue()),
            )
        }

        fun getMockResponse(createdAt: Long): Response {
            // Arrange
            val chatCompletion = mockk<ChatCompletion>()
            val params = mockk<ResponseCreateParams>()

            // Minimal setup to avoid exceptions
            every { chatCompletion.id() } returns "ext-id"
            every { chatCompletion.created() } returns createdAt
            every { chatCompletion.model() } returns "gpt-4"
            every { chatCompletion.choices() } returns emptyList()
            every { chatCompletion.usage() } returns
                Optional.of(
                    CompletionUsage
                        .builder()
                        .completionTokens(3)
                        .totalTokens(8)
                        .promptTokens(5)
                        .build(),
                )

            every { params.instructions() } returns Optional.empty()
            every { params.metadata() } returns Optional.empty()
            every { params.model() } returns ChatModel.of("gpt-4")
            every { params.temperature() } returns Optional.of(0.7)
            every { params._parallelToolCalls() } returns JsonField.of(false)
            every { params._tools() } returns
                JsonField.of(
                    listOf(
                        com.openai.models.responses.Tool.ofFunction(
                            com.openai.models.responses.FunctionTool
                                .builder()
                                .type(JsonValue.from("myTool"))
                                .name("myTool")
                                .strict(true)
                                .parameters(
                                    com.openai.models.responses.FunctionTool.Parameters
                                        .builder()
                                        .build(),
                                ).build(),
                        ),
                    ),
                )
            every { params.toolChoice() } returns Optional.empty()
            every { params.topP() } returns Optional.of(1.0)
            every { params.maxOutputTokens() } returns Optional.of(512)
            every { params.previousResponseId() } returns Optional.empty()
            every { params.reasoning() } returns Optional.empty()

            // Act
            return chatCompletion.toResponse(params)
        }
    }
}
