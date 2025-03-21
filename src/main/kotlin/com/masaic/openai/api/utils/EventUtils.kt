package com.masaic.openai.api.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.ResponseStreamEvent
import org.springframework.http.codec.ServerSentEvent

class EventUtils {

    companion object {
        val objectMapper = ObjectMapper()
        const val SPACE = " "

        fun convertEvent(event: ResponseStreamEvent): ServerSentEvent<String> {
            if (event.isAudioDelta()) {
                val audioDeltaEvent = event.asAudioDelta()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(audioDeltaEvent))
                    .event(SPACE + audioDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isAudioDone()) {
                val audioDoneEvent = event.asAudioDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(audioDoneEvent))
                    .event(SPACE + audioDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isAudioTranscriptDelta()) {
                val audioTranscriptDeltaEvent = event.asAudioTranscriptDelta()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(audioTranscriptDeltaEvent))
                    .event(SPACE + audioTranscriptDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isAudioTranscriptDone()) {
                val audioTranscriptDoneEvent = event.asAudioTranscriptDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(audioTranscriptDoneEvent))
                    .event(SPACE + audioTranscriptDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallCodeDelta()) {
                val codeInterpreterCallCodeDeltaEvent = event.asCodeInterpreterCallCodeDelta()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(codeInterpreterCallCodeDeltaEvent))
                    .event(SPACE + codeInterpreterCallCodeDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallCodeDone()) {
                val codeInterpreterCallCodeDoneEvent = event.asCodeInterpreterCallCodeDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(codeInterpreterCallCodeDoneEvent))
                    .event(SPACE + codeInterpreterCallCodeDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallCompleted()) {
                val codeInterpreterCallCompletedEvent = event.asCodeInterpreterCallCompleted()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(codeInterpreterCallCompletedEvent))
                    .event(SPACE + codeInterpreterCallCompletedEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallInProgress()) {
                val codeInterpreterCallInProgressEvent = event.asCodeInterpreterCallInProgress()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(codeInterpreterCallInProgressEvent))
                    .event(SPACE + codeInterpreterCallInProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallInterpreting()) {
                val codeInterpreterCallInterpretingEvent = event.asCodeInterpreterCallInterpreting()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(codeInterpreterCallInterpretingEvent))
                    .event(SPACE + codeInterpreterCallInterpretingEvent._type().asStringOrThrow()).build()
            }

            if (event.isCompleted()) {
                val completedEvent = event.asCompleted()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(completedEvent))
                    .event(SPACE + completedEvent._type().asStringOrThrow()).build()
            }

            if (event.isContentPartAdded()) {
                val contentPartAddedEvent = event.asContentPartAdded()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(contentPartAddedEvent))
                    .event(SPACE + contentPartAddedEvent._type().asStringOrThrow()).build()
            }

            if (event.isContentPartDone()) {
                val contentPartDoneEvent = event.asContentPartDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(contentPartDoneEvent))
                    .event(SPACE + contentPartDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isCreated()) {
                val createdEvent = event.asCreated()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(createdEvent))
                    .event(SPACE + createdEvent._type().asStringOrThrow()).build()
            }

            if (event.isError()) {
                val errorEvent = event.asError()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(errorEvent))
                    .event(SPACE + errorEvent._type().asStringOrThrow()).build()
            }

            if (event.isFileSearchCallCompleted()) {
                val fileSearchCallCompletedEvent = event.asFileSearchCallCompleted()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(fileSearchCallCompletedEvent))
                    .event(SPACE + fileSearchCallCompletedEvent._type().asStringOrThrow()).build()
            }

            if (event.isFileSearchCallInProgress()) {
                val fileSearchCallInProgressEvent = event.asFileSearchCallInProgress()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(fileSearchCallInProgressEvent))
                    .event(SPACE + fileSearchCallInProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isFileSearchCallSearching()) {
                val fileSearchCallSearchingEvent = event.asFileSearchCallSearching()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(fileSearchCallSearchingEvent))
                    .event(SPACE + fileSearchCallSearchingEvent._type().asStringOrThrow()).build()
            }

            if (event.isFunctionCallArgumentsDelta()) {
                val functionCallArgumentsDeltaEvent = event.asFunctionCallArgumentsDelta()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(functionCallArgumentsDeltaEvent))
                    .event(SPACE + functionCallArgumentsDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isFunctionCallArgumentsDone()) {
                val functionCallArgumentsDoneEvent = event.asFunctionCallArgumentsDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(functionCallArgumentsDoneEvent))
                    .event(SPACE + functionCallArgumentsDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isInProgress()) {
                val inProgressEvent = event.asInProgress()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(inProgressEvent))
                    .event(SPACE + inProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isFailed()) {
                val failedEvent = event.asFailed()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(failedEvent))
                    .event(SPACE + failedEvent._type().asStringOrThrow()).build()
            }

            if (event.isIncomplete()) {
                val incompleteEvent = event.asIncomplete()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(incompleteEvent))
                    .event(SPACE + incompleteEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputItemAdded()) {
                val outputItemAddedEvent = event.asOutputItemAdded()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(outputItemAddedEvent))
                    .event(SPACE + outputItemAddedEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputItemDone()) {
                val outputItemDoneEvent = event.asOutputItemDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(outputItemDoneEvent))
                    .event(SPACE + outputItemDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isRefusalDelta()) {
                val refusalDeltaEvent = event.asRefusalDelta()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(refusalDeltaEvent))
                    .event(SPACE + refusalDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isRefusalDone()) {
                val refusalDoneEvent = event.asRefusalDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(refusalDoneEvent))
                    .event(SPACE + refusalDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputTextAnnotationAdded()) {
                val outputTextAnnotationAddedEvent = event.asOutputTextAnnotationAdded()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(outputTextAnnotationAddedEvent))
                    .event(SPACE + outputTextAnnotationAddedEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputTextDelta()) {
                val outputTextDeltaEvent = event.asOutputTextDelta()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(outputTextDeltaEvent))
                    .event(SPACE + outputTextDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputTextDone()) {
                val outputTextDoneEvent = event.asOutputTextDone()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(outputTextDoneEvent))
                    .event(SPACE + outputTextDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isWebSearchCallCompleted()) {
                val webSearchCallCompletedEvent = event.asWebSearchCallCompleted()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(webSearchCallCompletedEvent))
                    .event(SPACE + webSearchCallCompletedEvent._type().asStringOrThrow()).build()
            }

            if (event.isWebSearchCallInProgress()) {
                val webSearchCallInProgressEvent = event.asWebSearchCallInProgress()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(webSearchCallInProgressEvent))
                    .event(SPACE + webSearchCallInProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isWebSearchCallSearching()) {
                val webSearchCallSearchingEvent = event.asWebSearchCallSearching()
                return ServerSentEvent.builder(SPACE + objectMapper.writeValueAsString(webSearchCallSearchingEvent))
                    .event(SPACE + webSearchCallSearchingEvent._type().asStringOrThrow()).build()
            }

            throw IllegalArgumentException("Unknown event type")
        }
    }
}