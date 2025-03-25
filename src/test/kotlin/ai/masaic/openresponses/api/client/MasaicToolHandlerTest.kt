package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.tool.ToolService
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.responses.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MasaicToolHandlerTest {
    private val toolService: ToolService = mockk()
    private val handler = MasaicToolHandler(toolService)

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
        // Let’s pretend the toolService recognizes and executes "myToolFunction"
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
        // Let’s pretend the toolService recognizes and executes "myToolFunction"
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
    }
}
