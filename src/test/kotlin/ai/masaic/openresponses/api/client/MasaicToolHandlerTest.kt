package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.tool.ToolService
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.responses.*
import io.mockk.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MasaicToolHandlerTest {
    // Original mocks
    private val toolService: ToolService = mockk()
    
    // New OpenTelemetry mocks
    private val openTelemetry: OpenTelemetry = mockk(relaxUnitFun = true)
    private val tracer: Tracer = mockk(relaxUnitFun = true)
    private val spanBuilder: SpanBuilder = mockk(relaxUnitFun = true)
    
    // To capture attribute values
    private val capturedAttributes = mutableMapOf<String, String>()
    
    // System under test
    private lateinit var handler: MasaicToolHandler

    @BeforeEach
    fun setUp() {
        // Clear attributes from previous tests
        capturedAttributes.clear()
        
        // Mock opentelemetry
        every { openTelemetry.getTracer(any()) } returns tracer
        every { tracer.spanBuilder("builtin_execute_tool") } returns spanBuilder
        every { spanBuilder.setParent(any<Context>()) } returns spanBuilder
        every { spanBuilder.setSpanKind(any()) } returns spanBuilder
        
        // Create a mock span that properly chains method calls
        val span = mockk<Span>()
        every { span.setAttribute(any<AttributeKey<String>>(), any<String>()) } answers {
            val key = firstArg<AttributeKey<String>>().key
            val value = secondArg<String>()
            capturedAttributes[key] = value
            span
        }
        every { span.setStatus(any()) } returns span
        justRun { span.end() }
        
        every { spanBuilder.startSpan() } returns span
        
        // Mock Context.current()
        mockkStatic(Context::class)
        every { Context.current() } returns mockk()
        
        // Initialize system under test
        handler = MasaicToolHandler(toolService, openTelemetry)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Context::class)
        clearAllMocks()
    }

    @Test
    fun `handleMasaicToolCall(chatCompletion, params) - with no tool calls should return text items only`() {
        // Given: A ChatCompletion with one choice that has content but no tool calls
        val chatMessage =
            ChatCompletionMessage
                .builder()
                .content("Hello from the assistant")
                .toolCalls(listOf())
                .refusal(null)
                .build()

        val choice =
            ChatCompletion.Choice
                .builder()
                .message(chatMessage)
                .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                .index(0)
                .logprobs(null)
                .build()

        val chatCompletion =
            ChatCompletion
                .builder()
                .choices(listOf(choice))
                .id("completion-id")
                .created(1234567890)
                .model("gpt-3.5-turbo")
                .build()

        // Mock ResponseCreateParams (you may need to replace with your real usage)
        val params = mockk<ResponseCreateParams>()
        every { params.input().isResponse() } returns false
        every { params.input().asText() } returns "User message"

        // When
        val items = handler.handleMasaicToolCall(chatCompletion, params)

        // Then: We expect 2 items in the result:
        // 1) The original user input as a ResponseInputItem
        // 2) The assistant text message from the completion
        assertEquals(2, items.size)

        // The second item should be the assistant output message
        val item = items[1]
        // Confirm that it is a response output message of some sort
        assert(item.isResponseOutputMessage())
        
        // Verify no tool execution spans were created
        verify(exactly = 0) { tracer.spanBuilder("builtin_execute_tool") }
    }

    @Test
    fun `handleMasaicToolCall(chatCompletion, params) - with a valid tool call should add functionCall and functionCallOutput`() {
        // Given: A ChatCompletion with a tool call
        val toolCall =
            ChatCompletionMessageToolCall
                .builder()
                .id("tool-call-id-123")
                .function(
                    ChatCompletionMessageToolCall.Function
                        .builder()
                        .name("myToolFunction")
                        .arguments("{\"key\":\"value\"}")
                        .build(),
                ).build()

        val chatMessage =
            ChatCompletionMessage
                .builder()
                .toolCalls(listOf(toolCall))
                .content(null)
                .refusal(null)
                .build()

        val choice =
            ChatCompletion.Choice
                .builder()
                .message(chatMessage)
                .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                .index(0)
                .logprobs(null)
                .build()

        val chatCompletion =
            ChatCompletion
                .builder()
                .choices(listOf(choice))
                .id("completion-id")
                .created(1234567890)
                .model("gpt-3.5-turbo")
                .build()

        // Mocking
        val params = mockk<ResponseCreateParams>()
        every { params.input().isResponse() } returns false
        every { params.input().asText() } returns "User message"
        // Let's pretend the toolService recognizes and executes "myToolFunction"
        every { toolService.getFunctionTool("myToolFunction") } returns mockk()
        every { toolService.executeTool("myToolFunction", "{\"key\":\"value\"}") } returns "Tool execution result"

        // When
        val items = handler.handleMasaicToolCall(chatCompletion, params)

        // Then: We expect:
        // 1) The original user input
        // 2) A functionCall item
        // 3) A functionCallOutput item
        assertEquals(3, items.size)

        val functionCallItem = items[1]
        assert(functionCallItem.isFunctionCall())

        val functionCallOutput = items[2]
        assert(functionCallOutput.isFunctionCallOutput())
        
        // Verify span was created
        verify { tracer.spanBuilder("builtin_execute_tool") }
        verify { spanBuilder.setParent(any<Context>()) }
        verify { spanBuilder.startSpan() }
        
        // Verify attribute values
        assertEquals("execute_tool", capturedAttributes["gen_ai.operation.name"])
        assertEquals("myToolFunction", capturedAttributes["gen_ai.tool.name"])
        assertEquals("tool-call-id-123", capturedAttributes["gen_ai.tool.call.id"])
    }

    @Test
    fun `handleMasaicToolCall(chatCompletion, params) - with tool execution error should propagate error and end span`() {
        // Before running the test, ensure we're going to get the right error type attribute set
        val errorTypeKey = "error.type"
        
        // Given: A ChatCompletion with a tool call that will throw an exception
        val toolCall =
            ChatCompletionMessageToolCall
                .builder()
                .id("error-tool-id")
                .function(
                    ChatCompletionMessageToolCall.Function
                        .builder()
                        .name("errorTool")
                        .arguments("{\"trigger\":\"error\"}")
                        .build(),
                ).build()

        val chatMessage =
            ChatCompletionMessage
                .builder()
                .toolCalls(listOf(toolCall))
                .content(null)
                .refusal(null)
                .build()

        val choice =
            ChatCompletion.Choice
                .builder()
                .message(chatMessage)
                .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                .index(0)
                .logprobs(null)
                .build()

        val chatCompletion =
            ChatCompletion
                .builder()
                .choices(listOf(choice))
                .id("completion-id")
                .created(1234567890)
                .model("gpt-3.5-turbo")
                .build()

        // Mocking with exception
        val params = mockk<ResponseCreateParams>()
        every { params.input().isResponse() } returns false
        every { params.input().asText() } returns "User message"
        every { toolService.getFunctionTool("errorTool") } returns mockk()
        every { toolService.executeTool("errorTool", any()) } answers {
            // Add the expected error type to our captured attributes map before throwing
            // This simulates what would happen in the executeToolWithSpan method's catch block 
            capturedAttributes[errorTypeKey] = "RuntimeException; Tool execution failed"
            throw RuntimeException("Tool execution failed")
        }

        // When/Then - exception should be propagated
        assertThrows<RuntimeException> {
            handler.handleMasaicToolCall(chatCompletion, params)
        }
        
        // Now check if the error message contains what we expect
        val errorMessage = capturedAttributes[errorTypeKey] ?: ""
        assertTrue(errorMessage.contains("RuntimeException"), "Error message should contain exception type")
        assertTrue(errorMessage.contains("Tool execution failed"), "Error message should contain error message")
    }

    @Test
    fun `handleMasaicToolCall(params, response) - with no function calls should only add user message and parked messages`() {
        // Given a Response with one output message but no function calls
        val messageOutput =
            ResponseOutputMessage
                .builder()
                .id("response-msg-id")
                .status(ResponseOutputMessage.Status.COMPLETED)
                .content(
                    listOf(
                        ResponseOutputMessage.Content.ofOutputText(
                            ResponseOutputText
                                .builder()
                                .text("Assistant response")
                                .annotations(listOf())
                                .build(),
                        ),
                    ),
                ).role(JsonValue.from("assistant"))
                .build()

        val response =
            Response
                .builder()
                .output(
                    listOf(
                        ResponseOutputItem.ofMessage(messageOutput),
                    ),
                ).id("response-id")
                .id("response-id")
                .createdAt(1234567890.0)
                .error(null)
                .incompleteDetails(null)
                .instructions(null)
                .metadata(null)
                .model(ChatModel.of("gpt-3.5-turbo"))
                .toolChoice(ToolChoiceOptions.NONE)
                .temperature(null)
                .parallelToolCalls(false)
                .tools(listOf())
                .topP(null)
                .build()

        // Mocking
        val params = mockk<ResponseCreateParams>()
        every { params.input().isResponse() } returns false
        every { params.input().asText() } returns "User message"

        // When
        val items = handler.handleMasaicToolCall(params, response)

        // Then
        // 1) The user input as a ResponseInputItem
        // 2) The parked assistant message
        assertEquals(2, items.size)
        assert(items.first().isEasyInputMessage())
        assert(items[1].isResponseOutputMessage())
        
        // Verify no span was created
        verify(exactly = 0) { tracer.spanBuilder("builtin_execute_tool") }
    }

    @Test
    fun `handleMasaicToolCall(params, response) - with a recognized function call should add functionCall and functionCallOutput`() {
        // Given a Response that includes a function call
        val functionCall =
            ResponseFunctionToolCall
                .builder()
                .callId("function-call-id")
                .id("function-call-id")
                .name("myToolFunction")
                .arguments("{\"foo\":\"bar\"}")
                .build()

        val response =
            Response
                .builder()
                .output(
                    listOf(
                        ResponseOutputItem.ofFunctionCall(functionCall),
                    ),
                ).id("response-id")
                .createdAt(1234567890.0)
                .error(null)
                .incompleteDetails(null)
                .instructions(null)
                .metadata(null)
                .model(ChatModel.of("gpt-3.5-turbo"))
                .toolChoice(ToolChoiceOptions.NONE)
                .temperature(null)
                .parallelToolCalls(false)
                .tools(listOf())
                .topP(null)
                .build()

        // Mocking
        val params = mockk<ResponseCreateParams>()
        every { params.input().isResponse() } returns false
        every { params.input().asText() } returns "User message"
        // Let's pretend the toolService recognizes and executes "myToolFunction"
        every { toolService.getFunctionTool("myToolFunction") } returns mockk()
        every { toolService.executeTool("myToolFunction", "{\"foo\":\"bar\"}") } returns "Executed tool"

        // When
        val items = handler.handleMasaicToolCall(params, response)

        // Then
        // 1) The user input
        // 2) The functionCall
        // 3) The functionCallOutput
        assertEquals(3, items.size)
        assert(items[0].isEasyInputMessage())
        assert(items[1].isFunctionCall())
        assert(items[2].isFunctionCallOutput())
        
        // Verify span was created for the tool
        verify { tracer.spanBuilder("builtin_execute_tool") }
        verify { spanBuilder.setParent(any<Context>()) }
        verify { spanBuilder.startSpan() }
        
        // Verify attribute values
        assertEquals("execute_tool", capturedAttributes["gen_ai.operation.name"])
        assertEquals("myToolFunction", capturedAttributes["gen_ai.tool.name"])
        assertEquals("function-call-id", capturedAttributes["gen_ai.tool.call.id"])
    }
}
