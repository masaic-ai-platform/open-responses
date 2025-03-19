package com.masaic.openai.api.client

import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextDeltaEvent
import com.openai.models.responses.ResponseTextDoneEvent

object ChatCompletionChunkConverter {

    /**
     * Converts a ChatCompletionChunk.Choice to a ServerSentEvent.
     *
     * @param chunk The ChatCompletionChunk.Choice to convert
     * @return The converted ServerSentEvent
     */
    fun toResponseStreamEvent(chunk: ChatCompletionChunk.Choice): List<ResponseStreamEvent> {
        return when {
            chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "stop" -> {
                listOf(
                    ResponseStreamEvent.ofOutputTextDone(
                        ResponseTextDoneEvent.builder()
                            .contentIndex(chunk.index())
                            .text("")
                            .outputIndex(chunk.index())
                            .itemId("0")
                            .build()
                    )
                )
            }
            chunk.delta().content().isPresent && chunk.delta().content().get().isNotBlank() -> {
                val delta = chunk.delta().content().get()
                val index = chunk.index()
                listOf(
                    ResponseStreamEvent.ofOutputTextDelta(
                        ResponseTextDeltaEvent.builder()
                            .contentIndex(index)
                            .outputIndex(0)
                            .itemId(index.toString())
                            .delta(delta)
                            .build()
                    )
                )
            }
            chunk.delta().toolCalls().isPresent && chunk.delta().toolCalls().get().isNotEmpty() -> {
                val toolCall = chunk.delta().toolCalls().get().first()
                listOf(
                    ResponseStreamEvent.ofFunctionCallArgumentsDelta(
                        ResponseFunctionCallArgumentsDeltaEvent.builder()
                            .outputIndex(toolCall.index())
                            .delta(toolCall.function().get().arguments().get())
                            .itemId(toolCall._id())
                            .putAllAdditionalProperties(toolCall._additionalProperties())
                            .build()
                    )
                )
            }
            chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "tool_calls" -> {

                listOf(
                    ResponseStreamEvent.ofFunctionCallArgumentsDone(
                        ResponseFunctionCallArgumentsDoneEvent.builder()
                            .outputIndex(0)
                            .arguments("")
                            .itemId("0")
                            .putAllAdditionalProperties(chunk._additionalProperties())
                            .build()
                    )
                )
            }
            else -> {
                listOf()
            }
        }
    }
}

/**
 * Extension function to convert ChatCompletionChunk.Choice to a ServerSentEvent.
 *
 * @return The converted ServerSentEvent
 */
fun ChatCompletionChunk.Choice.toResponseStreamEvent(): List<ResponseStreamEvent> {
    return ChatCompletionChunkConverter.toResponseStreamEvent(this)
}