package com.masaic.openai.api.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.ResponseStreamEvent
import org.springframework.http.codec.ServerSentEvent

class EventUtils {

    companion object {
        val objectMapper = ObjectMapper()

        fun convertEvent(event: ResponseStreamEvent): ServerSentEvent<String> {
            if (event.isAudioDelta()) {
                val audioDeltaEvent = event.asAudioDelta()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(audioDeltaEvent))
                    .event(audioDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isAudioDone()) {
                val audioDoneEvent = event.asAudioDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(audioDoneEvent))
                    .event(audioDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isAudioTranscriptDelta()) {
                val audioTranscriptDeltaEvent = event.asAudioTranscriptDelta()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(audioTranscriptDeltaEvent))
                    .event(audioTranscriptDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isAudioTranscriptDone()) {
                val audioTranscriptDoneEvent = event.asAudioTranscriptDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(audioTranscriptDoneEvent))
                    .event(audioTranscriptDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallCodeDelta()) {
                val codeInterpreterCallCodeDeltaEvent = event.asCodeInterpreterCallCodeDelta()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(codeInterpreterCallCodeDeltaEvent))
                    .event(codeInterpreterCallCodeDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallCodeDone()) {
                val codeInterpreterCallCodeDoneEvent = event.asCodeInterpreterCallCodeDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(codeInterpreterCallCodeDoneEvent))
                    .event(codeInterpreterCallCodeDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallCompleted()) {
                val codeInterpreterCallCompletedEvent = event.asCodeInterpreterCallCompleted()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(codeInterpreterCallCompletedEvent))
                    .event(codeInterpreterCallCompletedEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallInProgress()) {
                val codeInterpreterCallInProgressEvent = event.asCodeInterpreterCallInProgress()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(codeInterpreterCallInProgressEvent))
                    .event(codeInterpreterCallInProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isCodeInterpreterCallInterpreting()) {
                val codeInterpreterCallInterpretingEvent = event.asCodeInterpreterCallInterpreting()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(codeInterpreterCallInterpretingEvent))
                    .event(codeInterpreterCallInterpretingEvent._type().asStringOrThrow()).build()
            }

            if (event.isCompleted()) {
                val completedEvent = event.asCompleted()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(completedEvent))
                    .event(completedEvent._type().asStringOrThrow()).build()
            }

            if (event.isContentPartAdded()) {
                val contentPartAddedEvent = event.asContentPartAdded()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(contentPartAddedEvent))
                    .event(contentPartAddedEvent._type().asStringOrThrow()).build()
            }

            if (event.isContentPartDone()) {
                val contentPartDoneEvent = event.asContentPartDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(contentPartDoneEvent))
                    .event(contentPartDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isCreated()) {
                val createdEvent = event.asCreated()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(createdEvent))
                    .event(createdEvent._type().asStringOrThrow()).build()
            }

            if (event.isError()) {
                val errorEvent = event.asError()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(errorEvent))
                    .event(errorEvent._type().asStringOrThrow()).build()
            }

            if (event.isFileSearchCallCompleted()) {
                val fileSearchCallCompletedEvent = event.asFileSearchCallCompleted()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(fileSearchCallCompletedEvent))
                    .event(fileSearchCallCompletedEvent._type().asStringOrThrow()).build()
            }

            if (event.isFileSearchCallInProgress()) {
                val fileSearchCallInProgressEvent = event.asFileSearchCallInProgress()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(fileSearchCallInProgressEvent))
                    .event(fileSearchCallInProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isFileSearchCallSearching()) {
                val fileSearchCallSearchingEvent = event.asFileSearchCallSearching()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(fileSearchCallSearchingEvent))
                    .event(fileSearchCallSearchingEvent._type().asStringOrThrow()).build()
            }

            if (event.isFunctionCallArgumentsDelta()) {
                val functionCallArgumentsDeltaEvent = event.asFunctionCallArgumentsDelta()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(functionCallArgumentsDeltaEvent))
                    .event(functionCallArgumentsDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isFunctionCallArgumentsDone()) {
                val functionCallArgumentsDoneEvent = event.asFunctionCallArgumentsDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(functionCallArgumentsDoneEvent))
                    .event(functionCallArgumentsDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isInProgress()) {
                val inProgressEvent = event.asInProgress()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(inProgressEvent))
                    .event(inProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isFailed()) {
                val failedEvent = event.asFailed()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(failedEvent))
                    .event(failedEvent._type().asStringOrThrow()).build()
            }

            if (event.isIncomplete()) {
                val incompleteEvent = event.asIncomplete()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(incompleteEvent))
                    .event(incompleteEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputItemAdded()) {
                val outputItemAddedEvent = event.asOutputItemAdded()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(outputItemAddedEvent))
                    .event(outputItemAddedEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputItemDone()) {
                val outputItemDoneEvent = event.asOutputItemDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(outputItemDoneEvent))
                    .event(outputItemDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isRefusalDelta()) {
                val refusalDeltaEvent = event.asRefusalDelta()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(refusalDeltaEvent))
                    .event(refusalDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isRefusalDone()) {
                val refusalDoneEvent = event.asRefusalDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(refusalDoneEvent))
                    .event(refusalDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputTextAnnotationAdded()) {
                val outputTextAnnotationAddedEvent = event.asOutputTextAnnotationAdded()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(outputTextAnnotationAddedEvent))
                    .event(outputTextAnnotationAddedEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputTextDelta()) {
                val outputTextDeltaEvent = event.asOutputTextDelta()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(outputTextDeltaEvent))
                    .event(outputTextDeltaEvent._type().asStringOrThrow()).build()
            }

            if (event.isOutputTextDone()) {
                val outputTextDoneEvent = event.asOutputTextDone()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(outputTextDoneEvent))
                    .event(outputTextDoneEvent._type().asStringOrThrow()).build()
            }

            if (event.isWebSearchCallCompleted()) {
                val webSearchCallCompletedEvent = event.asWebSearchCallCompleted()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(webSearchCallCompletedEvent))
                    .event(webSearchCallCompletedEvent._type().asStringOrThrow()).build()
            }

            if (event.isWebSearchCallInProgress()) {
                val webSearchCallInProgressEvent = event.asWebSearchCallInProgress()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(webSearchCallInProgressEvent))
                    .event(webSearchCallInProgressEvent._type().asStringOrThrow()).build()
            }

            if (event.isWebSearchCallSearching()) {
                val webSearchCallSearchingEvent = event.asWebSearchCallSearching()
                return ServerSentEvent.builder(objectMapper.writeValueAsString(webSearchCallSearchingEvent))
                    .event(webSearchCallSearchingEvent._type().asStringOrThrow()).build()
            }

            throw IllegalArgumentException("Unknown event type")
        }
    }
}