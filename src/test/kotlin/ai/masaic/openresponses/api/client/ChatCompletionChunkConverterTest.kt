package ai.masaic.openresponses.api.client

import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

internal class ChatCompletionChunkConverterTest {
    @Test
    fun `toResponseStreamEvent returns text delta event when content is present`() {
        // Arrange
        val chunk = mockk<ChatCompletionChunk>(relaxed = true)
        val choice = mockk<ChatCompletionChunk.Choice>(relaxed = true)
        val delta = mockk<ChatCompletionChunk.Choice.Delta>(relaxed = true)

        every { chunk.choices() } returns listOf(choice)
        every { chunk.id() } returns "test_id"
        // Mock text content
        every { delta.content() } returns Optional.of("Hello, world!")
        every { choice.delta() } returns delta
        every { choice.index() } returns 0L

        // Act
        val result = ChatCompletionChunkConverter.toResponseStreamEvent(chunk)

        // Assert
        // We expect exactly one event: a text delta event
        assertEquals(1, result.size)
        val event = result.first()
        assert(event.isOutputTextDelta())
        val textDelta = event.asOutputTextDelta()
        assertEquals("Hello, world!", textDelta.delta())
        assertEquals(0L, textDelta.outputIndex())
        assertEquals("test_id", textDelta.itemId())
    }

    @Test
    fun `toResponseStreamEvent returns function call event when toolCalls are present with function name`() {
        // Arrange
        val chunk = mockk<ChatCompletionChunk>(relaxed = true)
        val choice = mockk<ChatCompletionChunk.Choice>(relaxed = true)
        val delta = mockk<ChatCompletionChunk.Choice.Delta>(relaxed = true)
        val toolCall = mockk<ChatCompletionChunk.Choice.Delta.ToolCall>(relaxed = true)
        val function = mockk<ChatCompletionChunk.Choice.Delta.ToolCall.Function>(relaxed = true)

        every { chunk.choices() } returns listOf(choice)
        every { chunk.id() } returns "test_id"
        every { choice.delta() } returns delta
        every { choice.index() } returns 1L
        // Mock tool call
        every { delta.toolCalls() } returns Optional.of(listOf(toolCall))
        every { function.name() } returns Optional.of("myFunction")
        every { function.arguments() } returns Optional.of("""{"arg1":"value1"}""")
        every { toolCall.function() } returns Optional.of(function)
        every { toolCall.index() } returns 1L
        every { toolCall.id() } returns Optional.of("tool_call_id")
        every { toolCall._additionalProperties() } returns emptyMap()

        // Act
        val result = ChatCompletionChunkConverter.toResponseStreamEvent(chunk)

        // Assert
        // We expect exactly one event: an OUTPUT_ITEM_ADDED (function call)
        assertEquals(1, result.size)
        val event = result.first()
        assert(event.isOutputItemAdded())
        val outputItemEvent = event.asOutputItemAdded()
        val outputItem = outputItemEvent.item()
        assert(outputItem.isFunctionCall())
        val toolCallObj = outputItem.asFunctionCall()
        assertEquals("myFunction", toolCallObj.name())
        assertEquals("""{"arg1":"value1"}""", toolCallObj.arguments())
        assertEquals("tool_call_id", toolCallObj.callId())
        assertEquals("test_id", toolCallObj.id())
        assertEquals(ResponseFunctionToolCall.Status.IN_PROGRESS, toolCallObj.status().get())
    }

    @Test
    fun `toResponseStreamEvent returns function call arguments delta event when toolCalls are present without function name`() {
        // Arrange
        val chunk = mockk<ChatCompletionChunk>(relaxed = true)
        val choice = mockk<ChatCompletionChunk.Choice>(relaxed = true)
        val delta = mockk<ChatCompletionChunk.Choice.Delta>(relaxed = true)
        val toolCall = mockk<ChatCompletionChunk.Choice.Delta.ToolCall>(relaxed = true)
        val function = mockk<ChatCompletionChunk.Choice.Delta.ToolCall.Function>(relaxed = true)

        every { chunk.choices() } returns listOf(choice)
        every { chunk.id() } returns "test_id"
        every { choice.delta() } returns delta
        every { choice.index() } returns 2L
        // Mock tool call missing a function name
        every { delta.toolCalls() } returns Optional.of(listOf(toolCall))
        every { function.name() } returns Optional.empty()
        every { function.arguments() } returns Optional.of("""{"arg2":"value2"}""")
        every { toolCall.function() } returns Optional.of(function)
        every { toolCall.index() } returns 2L
        every { toolCall._additionalProperties() } returns emptyMap()

        // Act
        val result = ChatCompletionChunkConverter.toResponseStreamEvent(chunk)

        // Assert
        // We expect exactly one event: a FUNCTION_CALL_ARGUMENTS_DELTA
        assertEquals(1, result.size)
        val event = result.first()
        assert(event.isFunctionCallArgumentsDelta())
        val argsDelta = event.asFunctionCallArgumentsDelta()
        assertEquals("""{"arg2":"value2"}""", argsDelta.delta())
        assertEquals("test_id", argsDelta.itemId())
    }

    @Test
    fun `toResponseStreamEvent returns tool call done event when finishReason is tool_calls`() {
        // Arrange
        val chunk = mockk<ChatCompletionChunk>(relaxed = true)
        val choice = mockk<ChatCompletionChunk.Choice>(relaxed = true)
        val delta = mockk<ChatCompletionChunk.Choice.Delta>(relaxed = true)

        every { chunk.choices() } returns listOf(choice)
        every { chunk.id() } returns "test_id"
        every { choice.delta() } returns delta
        every { choice._additionalProperties() } returns emptyMap()
        every { choice.index() } returns 3L
        // Mock finish reason
        every { choice.finishReason() } returns Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)

        // Act
        val result = ChatCompletionChunkConverter.toResponseStreamEvent(chunk)

        // Assert
        // We expect exactly one event: a FUNCTION_CALL_ARGUMENTS_DONE
        assertEquals(1, result.size)
        val event = result.first()
        assert(event.isFunctionCallArgumentsDone())
        val doneEvent = event.asFunctionCallArgumentsDone()
        assertEquals("", doneEvent.arguments())
        assertEquals("test_id", doneEvent.itemId())
    }

    @Test
    fun `toResponseStreamEvent returns empty list when no content, tool calls, or finishReason`() {
        // Arrange
        val chunk = mockk<ChatCompletionChunk>(relaxed = true)
        val choice = mockk<ChatCompletionChunk.Choice>(relaxed = true)
        val delta = mockk<ChatCompletionChunk.Choice.Delta>(relaxed = true)

        every { chunk.choices() } returns listOf(choice)
        every { chunk.id() } returns "test_id"
        every { choice.delta() } returns delta
        // No content
        every { delta.content() } returns Optional.empty()
        // No tool calls
        every { delta.toolCalls() } returns Optional.empty()
        // finishReason not "tool_calls"
        every { choice.finishReason() } returns Optional.empty()

        // Act
        val result = ChatCompletionChunkConverter.toResponseStreamEvent(chunk)

        // Assert
        assertEquals(0, result.size)
    }

    @Test
    fun `ChatCompletionChunk extension function returns the same result as converter`() {
        // Arrange
        val chunk = mockk<ChatCompletionChunk>(relaxed = true)
        every { chunk.choices() } returns emptyList()

        // Act
        val fromObject = ChatCompletionChunkConverter.toResponseStreamEvent(chunk)
        val fromExtension = chunk.toResponseStreamEvent()

        // Assert
        assertEquals(fromObject, fromExtension)
    }
}
