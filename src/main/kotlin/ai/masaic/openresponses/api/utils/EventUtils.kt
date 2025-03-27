package ai.masaic.openresponses.api.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.ResponseStreamEvent
import org.springframework.http.codec.ServerSentEvent

/**
 * Utility class for converting OpenAI API response events to ServerSentEvents.
 */
class EventUtils {
    companion object {
        private const val SPACE = " "

        /**
         * Converts a ResponseStreamEvent to a ServerSentEvent.
         *
         * @param event The event to convert
         * @return A ServerSentEvent containing the event data
         * @throws IllegalArgumentException if the event type is unknown
         */
        fun convertEvent(
            event: ResponseStreamEvent,
            payloadFormatter: PayloadFormatter,
            objectMapper: ObjectMapper,
        ): ServerSentEvent<String> {
            val eventType = getEventType(event)
            val eventData = SPACE + objectMapper.writeValueAsString(payloadFormatter.formatResponseStreamEvent(event))

            return ServerSentEvent
                .builder<String>(eventData)
                .event(SPACE + eventType)
                .build()
        }

        /**
         * Gets the event type from a ResponseStreamEvent.
         *
         * @param event The event to get the type from
         * @return The event type as a string
         * @throws IllegalArgumentException if the event type is unknown
         */
        private fun getEventType(event: ResponseStreamEvent): String =
            when {
                event.isAudioDelta() -> event.asAudioDelta()._type().asStringOrThrow()
                event.isAudioDone() -> event.asAudioDone()._type().asStringOrThrow()
                event.isAudioTranscriptDelta() -> event.asAudioTranscriptDelta()._type().asStringOrThrow()
                event.isAudioTranscriptDone() -> event.asAudioTranscriptDone()._type().asStringOrThrow()
                event.isCodeInterpreterCallCodeDelta() -> event.asCodeInterpreterCallCodeDelta()._type().asStringOrThrow()
                event.isCodeInterpreterCallCodeDone() -> event.asCodeInterpreterCallCodeDone()._type().asStringOrThrow()
                event.isCodeInterpreterCallCompleted() -> event.asCodeInterpreterCallCompleted()._type().asStringOrThrow()
                event.isCodeInterpreterCallInProgress() -> event.asCodeInterpreterCallInProgress()._type().asStringOrThrow()
                event.isCodeInterpreterCallInterpreting() -> event.asCodeInterpreterCallInterpreting()._type().asStringOrThrow()
                event.isCompleted() -> event.asCompleted()._type().asStringOrThrow()
                event.isContentPartAdded() -> event.asContentPartAdded()._type().asStringOrThrow()
                event.isContentPartDone() -> event.asContentPartDone()._type().asStringOrThrow()
                event.isCreated() -> event.asCreated()._type().asStringOrThrow()
                event.isError() -> event.asError()._type().asStringOrThrow()
                event.isFileSearchCallCompleted() -> event.asFileSearchCallCompleted()._type().asStringOrThrow()
                event.isFileSearchCallInProgress() -> event.asFileSearchCallInProgress()._type().asStringOrThrow()
                event.isFileSearchCallSearching() -> event.asFileSearchCallSearching()._type().asStringOrThrow()
                event.isFunctionCallArgumentsDelta() -> event.asFunctionCallArgumentsDelta()._type().asStringOrThrow()
                event.isFunctionCallArgumentsDone() -> event.asFunctionCallArgumentsDone()._type().asStringOrThrow()
                event.isInProgress() -> event.asInProgress()._type().asStringOrThrow()
                event.isFailed() -> event.asFailed()._type().asStringOrThrow()
                event.isIncomplete() -> event.asIncomplete()._type().asStringOrThrow()
                event.isOutputItemAdded() -> event.asOutputItemAdded()._type().asStringOrThrow()
                event.isOutputItemDone() -> event.asOutputItemDone()._type().asStringOrThrow()
                event.isRefusalDelta() -> event.asRefusalDelta()._type().asStringOrThrow()
                event.isRefusalDone() -> event.asRefusalDone()._type().asStringOrThrow()
                event.isOutputTextAnnotationAdded() -> event.asOutputTextAnnotationAdded()._type().asStringOrThrow()
                event.isOutputTextDelta() -> event.asOutputTextDelta()._type().asStringOrThrow()
                event.isOutputTextDone() -> event.asOutputTextDone()._type().asStringOrThrow()
                event.isWebSearchCallCompleted() -> event.asWebSearchCallCompleted()._type().asStringOrThrow()
                event.isWebSearchCallInProgress() -> event.asWebSearchCallInProgress()._type().asStringOrThrow()
                event.isWebSearchCallSearching() -> event.asWebSearchCallSearching()._type().asStringOrThrow()
                else -> throw IllegalArgumentException("Unknown event type")
            }
    }
}
