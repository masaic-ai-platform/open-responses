package ai.masaic.openresponses.api.client

import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseOutputItemAddedEvent
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextDeltaEvent

/**
 * Converts ChatCompletionChunk objects to ResponseStreamEvent objects.
 * This allows for seamless conversion between different API formats.
 */
object ChatCompletionChunkConverter {
    /**
     * Converts a ChatCompletionChunk to a list of ResponseStreamEvent objects.
     *
     * @param completion The ChatCompletionChunk to convert
     * @return A list of converted ResponseStreamEvent objects
     */
    fun toResponseStreamEvent(completion: ChatCompletionChunk): List<ResponseStreamEvent> =
        completion.choices().flatMap { chunk ->
            when {
                // Handle text content
                chunk.delta().content().isPresent &&
                    chunk
                        .delta()
                        .content()
                        .get()
                        .isNotBlank() -> {
                    createTextDeltaEvent(chunk.delta().content().get(), chunk.index(), completion.id())
                }
                // Handle tool calls
                chunk.delta().toolCalls().isPresent &&
                    chunk
                        .delta()
                        .toolCalls()
                        .get()
                        .isNotEmpty() -> {
                    createToolCallEvents(chunk.delta().toolCalls().get(), completion.id())
                }
                // Handle tool call completion
                chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "tool_calls" -> {
                    createToolCallDoneEvent(chunk.index(), completion.id(), chunk._additionalProperties())
                }
                // Default case
                else -> emptyList()
            }
        }

    /**
     * Creates a text delta event.
     *
     * @param content The text content
     * @param index The index of the choice
     * @param itemId The ID of the item
     * @return A list containing the text delta event
     */
    private fun createTextDeltaEvent(
        content: String,
        index: Long,
        itemId: String,
    ): List<ResponseStreamEvent> =
        listOf(
            ResponseStreamEvent.ofOutputTextDelta(
                ResponseTextDeltaEvent
                    .builder()
                    .contentIndex(index)
                    .outputIndex(index)
                    .itemId(itemId)
                    .delta(content)
                    .build(),
            ),
        )

    /**
     * Creates events for tool calls.
     *
     * @param toolCalls The tool calls to convert
     * @param completionId The ID of the completion
     * @return A list of events for the tool calls
     */
    private fun createToolCallEvents(
        toolCalls: List<ChatCompletionChunk.Choice.Delta.ToolCall>,
        completionId: String,
    ): List<ResponseStreamEvent> =
        toolCalls.map { toolCall ->
            if (toolCall.function().isPresent &&
                toolCall
                    .function()
                    .get()
                    .name()
                    .isPresent
            ) {
                // Create an output item added event for a function call
                createFunctionCallOutputItemEvent(toolCall, completionId)
            } else {
                // Create a function call arguments delta event
                createFunctionCallArgumentsDeltaEvent(toolCall, completionId)
            }
        }

    /**
     * Creates a function call output item event.
     *
     * @param toolCall The tool call to convert
     * @param completionId The ID of the completion
     * @return An output item added event
     */
    private fun createFunctionCallOutputItemEvent(
        toolCall: ChatCompletionChunk.Choice.Delta.ToolCall,
        completionId: String,
    ): ResponseStreamEvent =
        ResponseStreamEvent.ofOutputItemAdded(
            ResponseOutputItemAddedEvent
                .builder()
                .outputIndex(toolCall.index())
                .item(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .name(
                                toolCall
                                    .function()
                                    .get()
                                    .name()
                                    .get()
                                    .toString(),
                            ).arguments(
                                toolCall
                                    .function()
                                    .get()
                                    .arguments()
                                    .get()
                                    .toString(),
                            ).callId(toolCall.id().get())
                            .putAllAdditionalProperties(toolCall._additionalProperties())
                            .id(completionId)
                            .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                            .build(),
                    ),
                ).build(),
        )

    /**
     * Creates a function call arguments delta event.
     *
     * @param toolCall The tool call to convert
     * @param completionId The ID of the completion
     * @return A function call arguments delta event
     */
    private fun createFunctionCallArgumentsDeltaEvent(
        toolCall: ChatCompletionChunk.Choice.Delta.ToolCall,
        completionId: String,
    ): ResponseStreamEvent {
        val arguments =
            if (toolCall.function().isPresent &&
                toolCall
                    .function()
                    .get()
                    .arguments()
                    .isPresent
            ) {
                toolCall
                    .function()
                    .get()
                    .arguments()
                    .get()
            } else {
                ""
            }
        return ResponseStreamEvent.ofFunctionCallArgumentsDelta(
            ResponseFunctionCallArgumentsDeltaEvent
                .builder()
                .outputIndex(toolCall.index())
                .delta(arguments)
                .itemId(completionId)
                .putAllAdditionalProperties(toolCall._additionalProperties())
                .build(),
        )
    }

    /**
     * Creates a tool call done event.
     *
     * @param index The index of the choice
     * @param completionId The ID of the completion
     * @param additionalProperties Additional properties to include
     * @return A list containing the tool call done event
     */
    private fun createToolCallDoneEvent(
        index: Long,
        completionId: String,
        additionalProperties: Map<String, JsonValue>,
    ): List<ResponseStreamEvent> =
        listOf(
            ResponseStreamEvent.ofFunctionCallArgumentsDone(
                ResponseFunctionCallArgumentsDoneEvent
                    .builder()
                    .outputIndex(index)
                    .arguments("")
                    .itemId(completionId)
                    .putAllAdditionalProperties(additionalProperties)
                    .build(),
            ),
        )
}

/**
 * Extension function to convert a ChatCompletionChunk to a list of ResponseStreamEvent objects.
 *
 * @return A list of converted ResponseStreamEvent objects
 */
fun ChatCompletionChunk.toResponseStreamEvent(): List<ResponseStreamEvent> = ChatCompletionChunkConverter.toResponseStreamEvent(this)
