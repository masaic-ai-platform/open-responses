package com.masaic.openai.api.client

import com.masaic.openai.api.utils.EventUtils
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextDeltaEvent
import com.openai.models.responses.ResponseTextDoneEvent
import org.springframework.http.codec.ServerSentEvent

object ChatCompletionChunkConverter {

    /**
     * Converts a ChatCompletionChunk.Choice to a ServerSentEvent.
     *
     * @param chunk The ChatCompletionChunk.Choice to convert
     * @return The converted ServerSentEvent
     */
    fun toResponseStreamEvent(chunk: ChatCompletionChunk.Choice): ServerSentEvent<String> {
        return when {
            chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "stop" -> {
                EventUtils.convertEvent(
                    ResponseStreamEvent.ofOutputTextDone(
                        ResponseTextDoneEvent.builder()
                            .contentIndex(0)
                            .text("")
                            .outputIndex(0)
                            .itemId("0")
                            .build()
                    )
                )
            }
            chunk.delta().content().isPresent && chunk.delta().content().get().isNotBlank() -> {
                val delta = chunk.delta().content().get()
                val index = chunk.index()
                EventUtils.convertEvent(
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
                EventUtils.convertEvent(
                    ResponseStreamEvent.ofFunctionCallArgumentsDelta(
                        ResponseFunctionCallArgumentsDeltaEvent.builder()
                            .outputIndex(0)
                            .delta(toolCall.function().get().arguments().get())
                            .itemId(toolCall._id())
                            .putAllAdditionalProperties(toolCall._additionalProperties())
                            .build()
                    )
                )
            }
            chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "tool_calls" -> {
                EventUtils.convertEvent(
                    ResponseStreamEvent.ofFunctionCallArgumentsDone(
                        ResponseFunctionCallArgumentsDoneEvent.builder()
                            .outputIndex(0)
                            .arguments("TODO")
                            .itemId("0")
                            .putAllAdditionalProperties(mapOf())
                            .build()
                    )
                )
            }
            else -> {
                EventUtils.convertEvent(
                    ResponseStreamEvent.ofOutputTextDelta(
                        ResponseTextDeltaEvent.builder()
                            .contentIndex(0)
                            .outputIndex(0)
                            .itemId("0")
                            .delta("")
                            .build()
                    )
                )
            }
        }
    }
}

/**
 * Extension function to convert ChatCompletionChunk.Choice to a ServerSentEvent.
 *
 * @return The converted ServerSentEvent
 */
fun ChatCompletionChunk.Choice.toResponseStreamEvent(): ServerSentEvent<String>? {
    return ChatCompletionChunkConverter.toResponseStreamEvent(this)
}