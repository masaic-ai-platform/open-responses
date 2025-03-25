package ai.masaic.openresponses.api.client

import com.openai.core.JsonField
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.completions.CompletionUsage
import com.openai.models.responses.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class ChatCompletionConverterTest {
    @Test
    fun `toResponse returns COMPLETED status with single message output`() {
        // Arrange
        val chatCompletion = mockk<ChatCompletion>(relaxed = true)
        val choice = mockk<ChatCompletion.Choice>(relaxed = true)
        val message = mockk<ChatCompletionMessage>(relaxed = true)

        every { chatCompletion.id() } returns "completion-id"
        every { chatCompletion.created() } returns 1678901234 // arbitrary epoch seconds
        every { chatCompletion.model() } returns "gpt-4"
        every { chatCompletion.choices() } returns listOf(choice)
        every { chatCompletion.usage() } returns
            Optional.of(
                CompletionUsage
                    .builder()
                    .completionTokens(3)
                    .totalTokens(8)
                    .promptTokens(5)
                    .build(),
            )

        every { choice.index() } returns 0
        every { choice.finishReason().asString() } returns "stop" // typical normal stop
        every { choice.message() } returns message

        // No reasoning tags, just a simple message
        every { message.content() } returns Optional.of("Hello world!")
        every { message.toolCalls() } returns Optional.empty()
        every { message.audio() } returns Optional.empty()
        every { message.annotations() } returns Optional.empty()

        // Mock usage
        val usage = mockk<CompletionUsage>(relaxed = true)
        every { usage.promptTokens() } returns 5
        every { usage.completionTokens() } returns 3
        every { usage.totalTokens() } returns 8
        every { chatCompletion.usage() } returns Optional.of(usage)

        // Stub ResponseCreateParams
        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        // Act
        val response = ChatCompletionConverter.toResponse(chatCompletion, params)

        // Assert
        assertEquals("completion-id", response.id())
        assertEquals(ResponseStatus.COMPLETED, response.status().get())
        assertEquals(1, response.output().size)

        val outputItem = response.output().first()
        assert(outputItem.isMessage())

        val messageVal = outputItem.asMessage()
        assertEquals(
            "Hello world!",
            messageVal
                .content()
                .first()
                .asOutputText()
                .text(),
        )

        assert(response.error().isEmpty)
        assert(response.incompleteDetails().isEmpty)

        // Check usage mapping
        assertNotNull(response.usage())
        assertEquals(5, response.usage().get().inputTokens())
        assertEquals(3, response.usage().get().outputTokens())
        assertEquals(8, response.usage().get().totalTokens())
    }

    @Test
    fun `toResponse extracts reasoning from within think tags`() {
        // Arrange
        val chatCompletion = mockk<ChatCompletion>(relaxed = true)
        val choice = mockk<ChatCompletion.Choice>(relaxed = true)
        val message = mockk<ChatCompletionMessage>(relaxed = true)

        every { chatCompletion.id() } returns "completion-id"
        every { chatCompletion.created() } returns 1678901234
        every { chatCompletion.model() } returns "gpt-4"
        every { chatCompletion.choices() } returns listOf(choice)
        every { chatCompletion.usage() } returns
            Optional.of(
                CompletionUsage
                    .builder()
                    .completionTokens(3)
                    .totalTokens(8)
                    .promptTokens(5)
                    .build(),
            )

        every { choice.index() } returns 0
        every { choice.finishReason().asString() } returns "stop"
        every { choice.message() } returns message

        // Contains <think>some text</think>
        every { message.content() } returns Optional.of("Hello <think>secret thoughts</think> world!")
        every { message.toolCalls() } returns Optional.empty()
        every { message.audio() } returns Optional.empty()
        every { message.annotations() } returns Optional.empty()

        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        // Act
        val response = ChatCompletionConverter.toResponse(chatCompletion, params)

        // Assert
        // Expect 2 outputs: 1 message (with reasoning removed) + 1 reasoning item
        assertEquals(2, response.output().size)

        val firstOutput = response.output()[0]
        val secondOutput = response.output()[1]

        // First is a message item
        assert(firstOutput.isMessage())
        val msgVal = firstOutput.asMessage()
        // <think> portion should be removed
        assertEquals(
            "Hello  world!",
            msgVal
                .content()
                .first()
                .asOutputText()
                .text(),
        )

        // Second is the reasoning item
        assert(secondOutput.isReasoning())
        val reasoningVal = secondOutput.asReasoning()
        assertEquals("secret thoughts", reasoningVal.summary().first().text())
    }

    @Test
    fun `toResponse sets incomplete details if any choice has finishReason length`() {
        // Arrange
        val chatCompletion = mockk<ChatCompletion>(relaxed = true)
        val choice1 = mockk<ChatCompletion.Choice>(relaxed = true)
        val choice2 = mockk<ChatCompletion.Choice>(relaxed = true)
        val message1 = mockk<ChatCompletionMessage>(relaxed = true)
        val message2 = mockk<ChatCompletionMessage>(relaxed = true)

        every { chatCompletion.id() } returns "completion-id"
        every { chatCompletion.created() } returns 1678901234
        every { chatCompletion.model() } returns "gpt-4"
        every { chatCompletion.choices() } returns listOf(choice1, choice2)
        every { chatCompletion.usage() } returns
            Optional.of(
                CompletionUsage
                    .builder()
                    .completionTokens(3)
                    .totalTokens(8)
                    .promptTokens(5)
                    .build(),
            )

        every { choice1.index() } returns 0
        every { choice1.finishReason().asString() } returns "stop"
        every { choice1.message() } returns message1
        every { message1.content() } returns Optional.of("Message #1")
        every { message1.toolCalls() } returns Optional.empty()
        every { message1.audio() } returns Optional.empty()
        every { message1.annotations() } returns Optional.empty()

        every { choice2.index() } returns 1
        every { choice2.finishReason().asString() } returns "length"
        every { choice2.message() } returns message2
        every { message2.content() } returns Optional.of("Message #2")
        every { message2.toolCalls() } returns Optional.empty()
        every { message2.audio() } returns Optional.empty()
        every { message2.annotations() } returns Optional.empty()

        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        // Act
        val response = ChatCompletionConverter.toResponse(chatCompletion, params)

        // Assert
        assertEquals(ResponseStatus.INCOMPLETE, response.status().get())
        assert(response.incompleteDetails().isPresent)
        assertEquals(
            Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS,
            response
                .incompleteDetails()
                .get()
                .reason()
                .get(),
        )
        // Both messages end up in output
        assertEquals(2, response.output().size)
    }

    @Test
    fun `toResponse sets content_filter error if any choice has finishReason content_filter`() {
        // Arrange
        val chatCompletion = mockk<ChatCompletion>(relaxed = true)
        val choice1 = mockk<ChatCompletion.Choice>(relaxed = true)
        val choice2 = mockk<ChatCompletion.Choice>(relaxed = true)
        val message1 = mockk<ChatCompletionMessage>(relaxed = true)
        val message2 = mockk<ChatCompletionMessage>(relaxed = true)

        every { chatCompletion.id() } returns "completion-id"
        every { chatCompletion.created() } returns 1678901234
        every { chatCompletion.model() } returns "gpt-4"
        every { chatCompletion.choices() } returns listOf(choice1, choice2)
        every { chatCompletion.usage() } returns
            Optional.of(
                CompletionUsage
                    .builder()
                    .completionTokens(3)
                    .totalTokens(8)
                    .promptTokens(5)
                    .build(),
            )

        every { choice1.index() } returns 0
        every { choice1.finishReason().asString() } returns "stop"
        every { choice1.message() } returns message1
        every { message1.content() } returns Optional.of("Message #1")
        every { message1.toolCalls() } returns Optional.empty()
        every { message1.audio() } returns Optional.empty()
        every { message1.annotations() } returns Optional.empty()

        every { choice2.index() } returns 1
        every { choice2.finishReason().asString() } returns "content_filter"
        every { choice2.message() } returns message2
        every { message2.content() } returns Optional.of("Message #2")
        every { message2.toolCalls() } returns Optional.empty()
        every { message2.audio() } returns Optional.empty()
        every { message2.annotations() } returns Optional.empty()

        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        // Act
        val response = ChatCompletionConverter.toResponse(chatCompletion, params)

        // Assert
        assertEquals(ResponseStatus.FAILED, response.status().get())
        assert(response.incompleteDetails().isPresent)
        assertEquals(
            Response.IncompleteDetails.Reason.CONTENT_FILTER,
            response
                .incompleteDetails()
                .get()
                .reason()
                .get(),
        )
        assert(response.error().isPresent)
        assertEquals(ResponseError.Code.SERVER_ERROR, response.error().get().code())
        assertTrue(
            response
                .error()
                .get()
                .message()
                .contains("violated") == true,
        )

        // Both messages appear in output even if one triggered content_filter
        assertEquals(2, response.output().size)
    }

    @Test
    fun `toResponse handles toolCalls if present`() {
        // Arrange
        val chatCompletion = mockk<ChatCompletion>(relaxed = true)
        val choice = mockk<ChatCompletion.Choice>(relaxed = true)
        val message = mockk<ChatCompletionMessage>(relaxed = true)
        val toolCall = mockk<ChatCompletionMessageToolCall>(relaxed = true)
        val toolCallFunction = mockk<ChatCompletionMessageToolCall.Function>(relaxed = true)

        every { chatCompletion.id() } returns "completion-id"
        every { chatCompletion.created() } returns 1678901234
        every { chatCompletion.model() } returns "gpt-4"
        every { chatCompletion.choices() } returns listOf(choice)
        every { chatCompletion.usage() } returns
            Optional.of(
                CompletionUsage
                    .builder()
                    .completionTokens(3)
                    .totalTokens(8)
                    .promptTokens(5)
                    .build(),
            )

        every { choice.index() } returns 0
        every { choice.finishReason().asString() } returns "stop"
        every { choice.message() } returns message

        every { message.content() } returns Optional.of("Message #1")
        every { message.toolCalls() } returns Optional.of(listOf(toolCall))
        every { message.audio() } returns Optional.empty()
        every { message.annotations() } returns Optional.empty()

        every { toolCall.id() } returns "toolcall-123"
        every { toolCall.function() } returns toolCallFunction
        every { toolCallFunction.name() } returns "myFunction"
        every { toolCallFunction.arguments() } returns "{}"

        val params = mockk<ResponseCreateParams>(relaxed = true)
        every { params.instructions() } returns Optional.empty()
        every { params.metadata() } returns Optional.empty()
        every { params.model() } returns ChatModel.of("gpt-4")
        every { params.temperature() } returns Optional.of(0.7)
        every { params._parallelToolCalls() } returns JsonField.of(false)
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        // Act
        val response = ChatCompletionConverter.toResponse(chatCompletion, params)

        // Assert
        // Expect 2 items: the message + the function call
        assertEquals(2, response.output().size)

        val first = response.output()[0]
        val second = response.output()[1]

        assert(first.isMessage())
        assert(second.isFunctionCall())

        val functionCallItem = second.asFunctionCall()
        assertEquals("completion-id", functionCallItem.id())
        assertEquals("toolcall-123", functionCallItem.callId())
        assertEquals("myFunction", functionCallItem.name())
        assertEquals("{}", functionCallItem.arguments())
    }

    @Test
    fun `extension function toResponse calls converter`() {
        // Arrange
        val chatCompletion = mockk<ChatCompletion>()
        val params = mockk<ResponseCreateParams>()

        // Minimal setup to avoid exceptions
        every { chatCompletion.id() } returns "ext-id"
        every { chatCompletion.created() } returns 0
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
        every { params._tools() } returns JsonField.of(emptyList())
        every { params.toolChoice() } returns Optional.empty()
        every { params.topP() } returns Optional.of(1.0)
        every { params.maxOutputTokens() } returns Optional.of(512)
        every { params.previousResponseId() } returns Optional.empty()
        every { params.reasoning() } returns Optional.empty()

        // Act
        val response = chatCompletion.toResponse(params)

        // Assert
        // We just confirm it doesn't blow up and we get a plausible result
        assertEquals("ext-id", response.id())
        assertEquals(0.0, response.createdAt())
        assertEquals(ResponseStatus.COMPLETED, response.status().get())
        assertTrue(response.output().isEmpty())
    }
}
