package com.masaic.openai.api.client

import com.masaic.openai.api.utils.EventUtils
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextDeltaEvent
import com.openai.models.responses.ResponseTextDoneEvent
import org.springframework.http.codec.ServerSentEvent

object ChatCompletionChunkConverter {

    fun toResponseStreamEvent(chunk: ChatCompletionChunk.Choice): ServerSentEvent<String> {
        if (chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "stop") {
            return EventUtils.convertEvent(
                ResponseStreamEvent.ofOutputTextDone(
                    ResponseTextDoneEvent.builder().contentIndex(
                        0
                    )
                        .text("")
                        .outputIndex(0)
                        .itemId("0").build()
                )
            )
        }

        if (chunk.delta().content().isPresent && chunk.delta().content().get().isNotBlank()) {
            val delta = chunk.delta().content().get()
            val index = chunk.index()
            return EventUtils.convertEvent(
                ResponseStreamEvent.ofOutputTextDelta(
                    ResponseTextDeltaEvent.builder().contentIndex(
                        index
                    )
                        .outputIndex(0)
                        .itemId(index.toString())
                        .delta(delta).build()
                )
            )
        }

        return EventUtils.convertEvent(
            ResponseStreamEvent.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder().contentIndex(
                    0
                )
                    .outputIndex(0)
                    .itemId("0")
                    .delta("").build()
            )
        )
    }
}

fun ChatCompletionChunk.Choice.toResponseStreamEvent(): ServerSentEvent<String>? {
    return ChatCompletionChunkConverter.toResponseStreamEvent(this)
}