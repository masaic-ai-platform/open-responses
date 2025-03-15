package com.masaic.openai.api.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.ResponseStreamEvent

class EventUtils {

    companion object {
        val objectMapper = ObjectMapper()

        fun convertEvent(event: ResponseStreamEvent): String {
            if(event.isAudioDelta()) {
                val audioDeltaEvent = event.asAudioDelta()
                return "event: ${audioDeltaEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(audioDeltaEvent)}\n\n"
            }

            if(event.isAudioDone()) {
                val audioDoneEvent = event.asAudioDone()
                return "event: ${audioDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(audioDoneEvent)}\n\n"
            }

            if(event.isAudioTranscriptDelta()) {
                val audioTranscriptDeltaEvent = event.asAudioTranscriptDelta()
                return "event: ${audioTranscriptDeltaEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(audioTranscriptDeltaEvent)}\n\n"
            }

            if(event.isAudioTranscriptDone()) {
                val audioTranscriptDoneEvent = event.asAudioTranscriptDone()
                return "event: ${audioTranscriptDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(audioTranscriptDoneEvent)}\n\n"
            }

            if(event.isCodeInterpreterCallCodeDelta()) {
                val codeInterpreterCallCodeDeltaEvent = event.asCodeInterpreterCallCodeDelta()
                return "event: ${codeInterpreterCallCodeDeltaEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(codeInterpreterCallCodeDeltaEvent)}\n\n"
            }

            if(event.isCodeInterpreterCallCodeDone()) {
                val codeInterpreterCallCodeDoneEvent = event.asCodeInterpreterCallCodeDone()
                return "event: ${codeInterpreterCallCodeDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(codeInterpreterCallCodeDoneEvent)}\n\n"
            }

            if(event.isCodeInterpreterCallCompleted()) {
                val codeInterpreterCallCompletedEvent = event.asCodeInterpreterCallCompleted()
                return "event: ${codeInterpreterCallCompletedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(codeInterpreterCallCompletedEvent)}\n\n"
            }

            if(event.isCodeInterpreterCallInProgress()) {
                val codeInterpreterCallInProgressEvent = event.asCodeInterpreterCallInProgress()
                return "event: ${codeInterpreterCallInProgressEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(codeInterpreterCallInProgressEvent)}\n\n"
            }

            if(event.isCodeInterpreterCallInterpreting()) {
                val codeInterpreterCallInterpretingEvent = event.asCodeInterpreterCallInterpreting()
                return "event: ${codeInterpreterCallInterpretingEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(codeInterpreterCallInterpretingEvent)}\n\n"
            }

            if(event.isCompleted()) {
                val completedEvent = event.asCompleted()
                return "event: ${completedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(completedEvent)}\n\n"
            }

            if(event.isContentPartAdded()) {
                val contentPartAddedEvent = event.asContentPartAdded()
                return "event: ${contentPartAddedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(contentPartAddedEvent)}\n\n"
            }

            if(event.isContentPartDone()) {
                val contentPartDoneEvent = event.asContentPartDone()
                return "event: ${contentPartDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(contentPartDoneEvent)}\n\n"
            }

            if(event.isCreated()) {
                val createdEvent = event.asCreated()
                return "event: ${createdEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(createdEvent)}\n\n"
            }

            if(event.isError()) {
                val errorEvent = event.asError()
                return "event: ${errorEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(errorEvent)}\n\n"
            }

            if(event.isFileSearchCallCompleted()) {
                val fileSearchCallCompletedEvent = event.asFileSearchCallCompleted()
                return "event: ${fileSearchCallCompletedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(fileSearchCallCompletedEvent)}\n\n"
            }

            if(event.isFileSearchCallInProgress()) {
                val fileSearchCallInProgressEvent = event.asFileSearchCallInProgress()
                return "event: ${fileSearchCallInProgressEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(fileSearchCallInProgressEvent)}\n\n"
            }

            if(event.isFileSearchCallSearching()) {
                val fileSearchCallSearchingEvent = event.asFileSearchCallSearching()
                return "event: ${fileSearchCallSearchingEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(fileSearchCallSearchingEvent)}\n\n"
            }

            if(event.isFunctionCallArgumentsDelta()) {
                val functionCallArgumentsDeltaEvent = event.asFunctionCallArgumentsDelta()
                return "event: ${functionCallArgumentsDeltaEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(functionCallArgumentsDeltaEvent)}\n\n"
            }

            if(event.isFunctionCallArgumentsDone()) {
                val functionCallArgumentsDoneEvent = event.asFunctionCallArgumentsDone()
                return "event: ${functionCallArgumentsDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(functionCallArgumentsDoneEvent)}\n\n"
            }

            if(event.isInProgress()) {
                val inProgressEvent = event.asInProgress()
                return "event: ${inProgressEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(inProgressEvent)}\n\n"
            }

            if(event.isFailed()) {
                val failedEvent = event.asFailed()
                return "event: ${failedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(failedEvent)}\n\n"
            }

            if(event.isIncomplete()) {
                val incompleteEvent = event.asIncomplete()
                return "event: ${incompleteEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(incompleteEvent)}\n\n"
            }

            if(event.isOutputItemAdded()) {
                val outputItemAddedEvent = event.asOutputItemAdded()
                return "event: ${outputItemAddedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(outputItemAddedEvent)}\n\n"
            }

            if(event.isOutputItemDone()) {
                val outputItemDoneEvent = event.asOutputItemDone()
                return "event: ${outputItemDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(outputItemDoneEvent)}\n\n"
            }

            if(event.isRefusalDelta()) {
                val refusalDeltaEvent = event.asRefusalDelta()
                return "event: ${refusalDeltaEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(refusalDeltaEvent)}\n\n"
            }

            if(event.isRefusalDone()) {
                val refusalDoneEvent = event.asRefusalDone()
                return "event: ${refusalDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(refusalDoneEvent)}\n\n"
            }

            if(event.isOutputTextAnnotationAdded()) {
                val outputTextAnnotationAddedEvent = event.asOutputTextAnnotationAdded()
                return "event: ${outputTextAnnotationAddedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(outputTextAnnotationAddedEvent)}\n\n"
            }

            if(event.isOutputTextDelta()) {
                val outputTextDeltaEvent = event.asOutputTextDelta()
                return "event: ${outputTextDeltaEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(outputTextDeltaEvent)}\n\n"
            }

            if(event.isOutputTextDone()) {
                val outputTextDoneEvent = event.asOutputTextDone()
                return "event: ${outputTextDoneEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(outputTextDoneEvent)}\n\n"
            }

            if(event.isWebSearchCallCompleted()) {
                val webSearchCallCompletedEvent = event.asWebSearchCallCompleted()
                return "event: ${webSearchCallCompletedEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(webSearchCallCompletedEvent)}\n\n"
            }

            if(event.isWebSearchCallInProgress()) {
                val webSearchCallInProgressEvent = event.asWebSearchCallInProgress()
                return "event: ${webSearchCallInProgressEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(webSearchCallInProgressEvent)}\n\n"
            }

            if(event.isWebSearchCallSearching()) {
                val webSearchCallSearchingEvent = event.asWebSearchCallSearching()
                return "event: ${webSearchCallSearchingEvent._type().asStringOrThrow()}\ndata: ${objectMapper.writeValueAsString(webSearchCallSearchingEvent)}\n\n"
            }

            return ""
        }
    }
}