package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.utils.EventUtils
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MasaicStreamingService(
    private val toolHandler: MasaicToolHandler,
    private val parameterConverter: MasaicParameterConverter,
    private val toolService: ToolService,
    // Make these constructor params for easy mocking:
    private val allowedMaxToolCalls: Int = System.getenv("MASAIC_MAX_TOOL_CALLS")?.toInt() ?: 10,
    private val maxDuration: Long = System.getenv("MASAIC_MAX_STREAMING_TIMEOUT")?.toLong() ?: 60000L, // 60 seconds
    private val payloadFormatter: PayloadFormatter,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Creates a streaming completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     *
     * @param client OpenAIClient to interact with OpenAI.
     * @param initialParams Parameters for creating the completion.
     * @return Flow of ServerSentEvent<String> containing response chunks.
     */
    fun createCompletionStream(
        client: OpenAIClient,
        initialParams: ResponseCreateParams,
    ): Flow<ServerSentEvent<String>> =
        flow {
            var currentParams = initialParams
            val responseId = UUID.randomUUID().toString()
            var shouldContinue = true
            var inProgressEventFired = false
            val startTime = System.currentTimeMillis()

            // Convert input into a list of items if needed:
            val responseInputItems = buildInitialResponseItems(initialParams)

            // Immediately emit a created event before we begin:
            emitCreatedEventIfNeeded(currentParams, responseId)

            // Check for tool-call limits:
            if (tooManyToolCalls(responseInputItems)) {
                emitTooManyToolCallsError()
                throw UnsupportedOperationException(
                    "Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable.",
                )
            }

            // Main processing loop:
            while (shouldContinue) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > maxDuration) {
                    emitTimeoutError()
                    // End if we have a timeout
                    break
                }

                // Perform one iteration, possibly update `currentParams` and `shouldContinue`
                val iterationResult =
                    executeStreamingIteration(
                        client,
                        currentParams,
                        responseId,
                        inProgressEventFired,
                    )

                currentParams = iterationResult.updatedParams
                shouldContinue = iterationResult.shouldContinue
                if (iterationResult.inProgressFired) {
                    inProgressEventFired = true
                }
            }
        }

    /**
     * Encapsulates a single iteration of streaming:
     *   - Creates the streaming call via callbackFlow
     *   - Collects all events (text/function-calls)
     *   - Returns a result indicating whether to continue
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.executeStreamingIteration(
        client: OpenAIClient,
        params: ResponseCreateParams,
        responseId: String,
        alreadyInProgressEventFired: Boolean,
    ): IterationResult {
        var nextIteration = false
        var updatedParams = params
        var inProgressFired = alreadyInProgressEventFired

        // We'll collect SSE events from the streaming call:
        callbackFlow {
            val subscription =
                client
                    .async()
                    .chat()
                    .completions()
                    .createStreaming(parameterConverter.prepareCompletion(params))

            val functionCallAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
            val textAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
            val responseOutputItemAccumulator = mutableListOf<ResponseOutputItem>()
            val internalToolItemIds = mutableSetOf<String>()
            val functionNameAccumulator = mutableMapOf<Long, Pair<String, String>>()

            subscription.subscribe { completion ->
                if (!completion._choices().isMissing()) {
                    // Fire in-progress event if we haven't:
                    if (!inProgressFired) {
                        trySend(
                            EventUtils.convertEvent(
                                ResponseStreamEvent.ofInProgress(
                                    ResponseInProgressEvent
                                        .builder()
                                        .response(
                                            ChatCompletionConverter.buildIntermediateResponse(
                                                params,
                                                ResponseStatus.IN_PROGRESS,
                                                responseId,
                                            ),
                                        ).build(),
                                ),
                                payloadFormatter,
                                objectMapper,
                            ),
                        ).isSuccess
                        inProgressFired = true
                    }

                    // Check if we have a stop/length/content_filter reason
                    if (completion.choices().any { choice ->
                            choice.finishReason().isPresent &&
                                listOf("stop", "length", "content_filter")
                                    .contains(choice.finishReason().get().asString())
                        }
                    ) {
                        // Process any text so far:
                        handleTextCompletion(textAccumulator, responseOutputItemAccumulator)

                        // Evaluate which finish reason we have:
                        val finishReason =
                            completion
                                .choices()
                                .find { it.finishReason().isPresent }
                                ?.finishReason()
                                ?.get()
                                ?.asString()
                        when (finishReason) {
                            "stop" -> {
                                val finalResponse =
                                    ChatCompletionConverter.buildFinalResponse(
                                        params,
                                        ResponseStatus.COMPLETED,
                                        responseId,
                                        responseOutputItemAccumulator,
                                    )
                                trySend(
                                    EventUtils.convertEvent(
                                        ResponseStreamEvent.ofCompleted(
                                            ResponseCompletedEvent
                                                .builder()
                                                .response(finalResponse)
                                                .build(),
                                        ),
                                        payloadFormatter,
                                        objectMapper,
                                    ),
                                )
                            }
                            "length", "content_filter" -> {
                                val incompleteDetails =
                                    if (finishReason == "length") {
                                        Response.IncompleteDetails
                                            .builder()
                                            .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                                            .build()
                                    } else {
                                        Response.IncompleteDetails
                                            .builder()
                                            .reason(Response.IncompleteDetails.Reason.CONTENT_FILTER)
                                            .build()
                                    }
                                val finalResponse =
                                    ChatCompletionConverter.buildFinalResponse(
                                        params,
                                        ResponseStatus.INCOMPLETE,
                                        responseId,
                                        responseOutputItemAccumulator,
                                        incompleteDetails,
                                    )
                                trySend(
                                    EventUtils.convertEvent(
                                        ResponseStreamEvent.ofIncomplete(
                                            ResponseIncompleteEvent
                                                .builder()
                                                .response(finalResponse)
                                                .build(),
                                        ),
                                        payloadFormatter,
                                        objectMapper,
                                    ),
                                )
                            }
                        }
                        nextIteration = false
                    } else {
                        // Ongoing streaming chunks
                        convertAndPublish(
                            completion,
                            functionCallAccumulator,
                            textAccumulator,
                            responseOutputItemAccumulator,
                            internalToolItemIds,
                            functionNameAccumulator,
                        )

                        // If we detect tool_calls:
                        if (completion.choices().any { choice ->
                                choice.finishReason().isPresent && choice.finishReason().get().asString() == "tool_calls"
                            }
                        ) {
                            // Process text so far, put it at the beginning
                            handleTextCompletion(textAccumulator, responseOutputItemAccumulator, prepend = true)

                            val finalResponse =
                                ChatCompletionConverter.buildFinalResponse(
                                    params,
                                    ResponseStatus.COMPLETED,
                                    responseId,
                                    responseOutputItemAccumulator,
                                )

                            if (internalToolItemIds.isEmpty()) {
                                // No calls to actually handle
                                nextIteration = false
                                trySend(
                                    EventUtils.convertEvent(
                                        ResponseStreamEvent.ofCompleted(
                                            ResponseCompletedEvent
                                                .builder()
                                                .response(finalResponse)
                                                .build(),
                                        ),
                                        payloadFormatter,
                                        objectMapper,
                                    ),
                                )
                            } else {
                                // Actually handle these calls
                                val toolResponseItems = toolHandler.handleMasaicToolCall(params, finalResponse)
                                updatedParams =
                                    params
                                        .toBuilder()
                                        .input(ResponseCreateParams.Input.ofResponse(toolResponseItems))
                                        .build()

                                // We'll do another iteration
                                nextIteration = true
                                close()
                            }
                        }
                    }
                }
            }

            launch {
                subscription.onCompleteFuture().await()
                close()
            }

            awaitClose {
                subscription.onCompleteFuture().cancel(true)
            }
        }.collect { event ->
            emit(event)
        }

        return IterationResult(
            shouldContinue = nextIteration,
            updatedParams = updatedParams,
            inProgressFired = inProgressFired,
        )
    }

    /**
     * Helper model to store iteration results.
     */
    private data class IterationResult(
        val shouldContinue: Boolean,
        val updatedParams: ResponseCreateParams,
        val inProgressFired: Boolean,
    )

    /**
     * Build the initial list of input items from the [initialParams].
     */
    private fun buildInitialResponseItems(initialParams: ResponseCreateParams): MutableList<ResponseInputItem> =
        if (initialParams.input().isResponse()) {
            initialParams.input().asResponse().toMutableList()
        } else {
            mutableListOf(
                ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage
                        .builder()
                        .content(initialParams.input().asText())
                        .role(EasyInputMessage.Role.USER)
                        .build(),
                ),
            )
        }

    /**
     * Emits a 'created' event to the flow's collector.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.emitCreatedEventIfNeeded(
        currentParams: ResponseCreateParams,
        responseId: String,
    ) {
        emit(
            EventUtils.convertEvent(
                ResponseStreamEvent.ofCreated(
                    ResponseCreatedEvent
                        .builder()
                        .response(
                            ChatCompletionConverter.buildIntermediateResponse(
                                currentParams,
                                ResponseStatus.IN_PROGRESS,
                                responseId,
                            ),
                        ).build(),
                ),
                payloadFormatter,
                objectMapper,
            ),
        )
    }

    /**
     * Checks if the tool-call limit is exceeded.
     */
    private fun tooManyToolCalls(inputItems: List<ResponseInputItem>): Boolean = inputItems.count { it.isFunctionCall() } > allowedMaxToolCalls

    /**
     * Emits an error for exceeding the tool call limit.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.emitTooManyToolCallsError() {
        emit(
            EventUtils.convertEvent(
                ResponseStreamEvent.ofError(
                    ResponseErrorEvent
                        .builder()
                        .message(
                            "Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable.",
                        ).code("too_many_tool_calls")
                        .param(null)
                        .build(),
                ),
                payloadFormatter,
                objectMapper,
            ),
        )
    }

    /**
     * Emits an error for a timeout condition.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.emitTimeoutError() {
        emit(
            EventUtils.convertEvent(
                ResponseStreamEvent.ofError(
                    ResponseErrorEvent
                        .builder()
                        .message(
                            "Timeout while processing. Increase the timeout limit by setting MASAIC_MAX_STREAMING_TIMEOUT environment variable.",
                        ).code("timeout")
                        .param(null)
                        .type(JsonValue.from("response.error"))
                        .build(),
                ),
                payloadFormatter,
                objectMapper,
            ),
        )
    }

    /**
     * Processes the accumulated text, sending events and storing final text output.
     */
    private fun ProducerScope<ServerSentEvent<String>>.handleTextCompletion(
        textAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        prepend: Boolean = false,
    ) {
        if (textAccumulator.isNotEmpty()) {
            textAccumulator.forEach { (index, events) ->
                val content = events.joinToString("") { it.asOutputTextDelta().delta() }

                trySend(
                    EventUtils.convertEvent(
                        ResponseStreamEvent.ofOutputTextDone(
                            ResponseTextDoneEvent
                                .builder()
                                .contentIndex(index)
                                .text(content)
                                .outputIndex(index)
                                .itemId(events.first().asOutputTextDelta().itemId())
                                .build(),
                        ),
                        payloadFormatter,
                        objectMapper,
                    ),
                )
            }

            // Combine into one message
            val singleMessage =
                ResponseOutputItem.ofMessage(
                    ResponseOutputMessage
                        .builder()
                        .content(
                            textAccumulator.map {
                                ResponseOutputMessage.Content.ofOutputText(
                                    ResponseOutputText
                                        .builder()
                                        .text(it.value.joinToString("") { e -> e.asOutputTextDelta().delta() })
                                        .annotations(listOf())
                                        .build(),
                                )
                            },
                        ).id(UUID.randomUUID().toString())
                        .status(ResponseOutputMessage.Status.COMPLETED)
                        .role(JsonValue.from("assistant"))
                        .build(),
                )

            // Put at the front or back of the accumulator
            if (prepend) {
                responseOutputItemAccumulator.add(0, singleMessage)
            } else {
                responseOutputItemAccumulator.add(singleMessage)
            }

            textAccumulator.clear()
        }
    }

    /**
     * Converts incoming chunk into appropriate [ResponseStreamEvent]s and sends them.
     */
    private fun ProducerScope<ServerSentEvent<String>>.convertAndPublish(
        completion: ChatCompletionChunk,
        functionCallAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        textAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        internalToolItemIds: MutableSet<String>,
        functionNameAccumulator: MutableMap<Long, Pair<String, String>>,
    ) {
        completion.toResponseStreamEvent().forEach { event ->
            when {
                event.isFunctionCallArgumentsDelta() -> {
                    handleFunctionCallDelta(event, functionCallAccumulator, internalToolItemIds, completion)
                }
                event.isOutputItemAdded() && event.asOutputItemAdded().item().isFunctionCall() -> {
                    handleOutputItemAdded(event, functionNameAccumulator, responseOutputItemAccumulator, internalToolItemIds)
                }
                event.isOutputTextDelta() -> {
                    handleOutputTextDelta(event, textAccumulator)
                }
                event.isFunctionCallArgumentsDone() -> {
                    handleFunctionCallDone(
                        functionCallAccumulator,
                        functionNameAccumulator,
                        responseOutputItemAccumulator,
                        internalToolItemIds,
                        completion,
                    )
                }
                event.isOutputItemDone() -> {
                    // Add final item
                    responseOutputItemAccumulator.add(event.asOutputItemDone().item())
                    trySend(EventUtils.convertEvent(event, payloadFormatter, objectMapper))
                }
                else -> {
                    trySend(EventUtils.convertEvent(event, payloadFormatter, objectMapper))
                }
            }
        }
    }

    private fun ProducerScope<ServerSentEvent<String>>.handleFunctionCallDelta(
        event: ResponseStreamEvent,
        functionCallAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        internalToolItemIds: MutableSet<String>,
        completion: ChatCompletionChunk,
    ) {
        val idx = event.asFunctionCallArgumentsDelta().outputIndex()
        functionCallAccumulator.getOrPut(idx) { mutableListOf() }.add(event)

        // If not an internal tool, forward event
        if (!internalToolItemIds.contains(completion.id())) {
            trySend(EventUtils.convertEvent(event, payloadFormatter, objectMapper))
        }
    }

    private fun ProducerScope<ServerSentEvent<String>>.handleOutputItemAdded(
        event: ResponseStreamEvent,
        functionNameAccumulator: MutableMap<Long, Pair<String, String>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        internalToolItemIds: MutableSet<String>,
    ) {
        val functionCall = event.asOutputItemAdded().item().asFunctionCall()
        val functionName = functionCall.name()
        val outputIndex = event.asOutputItemAdded().outputIndex()

        functionNameAccumulator[outputIndex] = Pair(functionName, functionCall.callId())

        // If a recognized function, mark internal
        if (toolService.getFunctionTool(functionName) != null) {
            internalToolItemIds.add(functionCall.id())
        }

        // If arguments are not blank, treat it as a complete function call
        if (functionCall.arguments().isNotBlank()) {
            responseOutputItemAccumulator.add(
                ResponseOutputItem.ofFunctionCall(
                    ResponseFunctionToolCall
                        .builder()
                        .name(functionName)
                        .arguments(functionCall.arguments())
                        .callId(functionCall.callId())
                        .id(functionCall.id())
                        .status(ResponseFunctionToolCall.Status.COMPLETED)
                        .putAllAdditionalProperties(functionCall._additionalProperties())
                        .type(JsonValue.from("function_call"))
                        .build(),
                ),
            )
        }
        trySend(EventUtils.convertEvent(event, payloadFormatter, objectMapper))
    }

    private fun ProducerScope<ServerSentEvent<String>>.handleOutputTextDelta(
        event: ResponseStreamEvent,
        textAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
    ) {
        val idx = event.asOutputTextDelta().outputIndex()
        textAccumulator.getOrPut(idx) { mutableListOf() }.add(event)
        trySend(EventUtils.convertEvent(event, payloadFormatter, objectMapper))
    }

    private fun ProducerScope<ServerSentEvent<String>>.handleFunctionCallDone(
        functionCallAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        functionNameAccumulator: MutableMap<Long, Pair<String, String>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        internalToolItemIds: MutableSet<String>,
        completion: ChatCompletionChunk,
    ) {
        functionCallAccumulator.forEach { (key, events) ->
            val content = events.joinToString("") { it.asFunctionCallArgumentsDelta().delta() }

            // If not an internal tool, forward the event
            if (!internalToolItemIds.contains(completion.id())) {
                trySend(
                    EventUtils.convertEvent(
                        ResponseStreamEvent.ofFunctionCallArgumentsDone(
                            ResponseFunctionCallArgumentsDoneEvent
                                .builder()
                                .outputIndex(key)
                                .arguments(content)
                                .itemId(events.first().asFunctionCallArgumentsDelta().itemId())
                                .putAllAdditionalProperties(events.first().asFunctionCallArgumentsDelta()._additionalProperties())
                                .build(),
                        ),
                        payloadFormatter,
                        objectMapper,
                    ),
                )
            }

            // Add the function call if we don't already have it
            val (name, callId) = functionNameAccumulator[key] ?: ("" to "")
            val alreadyAdded =
                responseOutputItemAccumulator
                    .filter { it.isFunctionCall() }
                    .any { it.asFunctionCall().name() == name }

            if (!alreadyAdded) {
                responseOutputItemAccumulator.add(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .name(name)
                            .arguments(content)
                            .callId(callId)
                            .id(events.first().asFunctionCallArgumentsDelta().itemId())
                            .status(ResponseFunctionToolCall.Status.COMPLETED)
                            .putAllAdditionalProperties(events.first().asFunctionCallArgumentsDelta()._additionalProperties())
                            .type(JsonValue.from("function_call"))
                            .build(),
                    ),
                )
            }
        }
    }
}
