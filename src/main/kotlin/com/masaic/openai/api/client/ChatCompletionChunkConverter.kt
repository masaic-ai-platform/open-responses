package com.masaic.openai.api.client

import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseOutputItemAddedEvent
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextDeltaEvent

object ChatCompletionChunkConverter {

    /**
     * Converts a ChatCompletionChunk.Choice to a ServerSentEvent.
     *
     * @param completion The ChatCompletionChunk to convert
     * @return The converted ServerSentEvent
     */
    fun toResponseStreamEvent(completion: ChatCompletionChunk): List<ResponseStreamEvent> {
        return completion.choices().flatMap {chunk ->
            when {
                chunk.delta().content().isPresent && chunk.delta().content().get().isNotBlank() -> {
                    val delta = chunk.delta().content().get()
                    val index = chunk.index()
                    listOf(
                        ResponseStreamEvent.ofOutputTextDelta(
                            ResponseTextDeltaEvent.builder()
                                .contentIndex(index) //TODO: Check if this is correct
                                .outputIndex(index)
                                .itemId(completion._id())
                                .delta(delta)
                                .build()
                        )
                    )
                }

                chunk.delta().toolCalls().isPresent && chunk.delta().toolCalls().get().isNotEmpty() -> {
                    chunk.delta().toolCalls().get().map {  toolCall ->

                        if(toolCall.function().isPresent && toolCall.function().get().name().isPresent){
                            ResponseStreamEvent.ofOutputItemAdded(
                                ResponseOutputItemAddedEvent.builder()
                                    .outputIndex(toolCall.index())
                                    .item(ResponseOutputItem.ofFunctionCall(
                                        ResponseFunctionToolCall.builder()
                                            .name(toolCall.function().get().name().get())
                                            .arguments(toolCall.function().get().arguments().get())
                                            .callId(toolCall._id())
                                            .putAllAdditionalProperties(toolCall._additionalProperties())
                                            .id(completion._id())
                                            .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                            .build()
                                    ))
                                    .build()
                            )
                        }
                        else {
                            ResponseStreamEvent.ofFunctionCallArgumentsDelta(
                                ResponseFunctionCallArgumentsDeltaEvent.builder()
                                    .outputIndex(toolCall.index())
                                    .delta(toolCall.function().get().arguments().get())
                                    .itemId(completion._id())
                                    .putAllAdditionalProperties(toolCall._additionalProperties())
                                    .build()
                            )
                        }
                    }
                }

                chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "tool_calls" -> {
                    listOf(
                        ResponseStreamEvent.ofFunctionCallArgumentsDone(
                            ResponseFunctionCallArgumentsDoneEvent.builder()
                                .outputIndex(chunk.index())
                                .arguments("")
                                .itemId(completion._id())
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
}

/**
 * Extension function to convert ChatCompletionChunk.Choice to a ServerSentEvent.
 *
 * @return The converted ServerSentEvent
 */
fun ChatCompletionChunk.toResponseStreamEvent(): List<ResponseStreamEvent> {
    return ChatCompletionChunkConverter.toResponseStreamEvent(this)
}