package com.masaic.openai.api.client

import com.masaic.openai.api.utils.EventUtils
import com.masaic.openai.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.responses.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Handles streaming operations for OpenAI API responses.
 * This service is responsible for creating and managing streaming completion flows.
 */
@Service
class MasaicStreamingService(
    private val toolHandler: MasaicToolHandler,
    private val parameterConverter: MasaicParameterConverter,
    private val toolService: ToolService
) {
    private val allowedMaxToolCalls = System.getenv("MASAIC_MAX_TOOL_CALLS")?.toInt() ?: 10
    private val maxDuration: Long =
        System.getenv("MASAIC_MAX_STREAMING_TIMEOUT")?.toLong() ?: 60000L // 60 seconds max total processing time

    /**
     * Creates a streaming completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     *
     * @param initialParams Parameters for creating the completion
     * @return Flow of ServerSentEvents containing response chunks
     */
    fun createCompletionStream(
        client: OpenAIClient,
        initialParams: ResponseCreateParams
    ): Flow<ServerSentEvent<String>> = flow {
        var currentParams = initialParams
        var responseId = UUID.randomUUID().toString()
        var shouldContinue = true
        var inProgressEventFired = false
        // Timeout mechanism
        val startTime = System.currentTimeMillis()

        val responseInputItems = if (initialParams.input().isResponse()) initialParams.input().asResponse()
            .toMutableList() else mutableListOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().content(initialParams.input().asText()).role(EasyInputMessage.Role.USER)
                    .build()
            )
        )

        if (currentParams == initialParams) {
            emit(
                EventUtils.convertEvent(
                    ResponseStreamEvent.ofCreated(
                        ResponseCreatedEvent.builder()
                            .response(
                                ChatCompletionConverter.buildIntermediateResponse(
                                    currentParams,
                                    ResponseStatus.IN_PROGRESS,
                                    responseId
                                )
                            )
                            .build()
                    )
                )
            )
        }

        // Check for tool call limit
        if (responseInputItems.filter { it.isFunctionCall() }.size > allowedMaxToolCalls) {
            emit(
                EventUtils.convertEvent(
                    ResponseStreamEvent.ofError(
                        ResponseErrorEvent.builder()
                            .message("Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable.")
                            .build()
                    )
                )
            )
            throw UnsupportedOperationException("Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable.")
        }

        while (shouldContinue) {
            // Create a mutable variable to track whether to continue after this iteration
            var nextIteration = false
            val elapsedTime = System.currentTimeMillis() - startTime

            // Check for timeout
            if (elapsedTime > maxDuration) {
                emit(
                    EventUtils.convertEvent(
                        ResponseStreamEvent.ofError(
                            ResponseErrorEvent.builder()
                                .message("Timeout while processing. Increase the timeout limit by setting MASAIC_MAX_STREAMING_TIMEOUT environment variable.")
                                .build()
                        )
                    )
                )
            }

            // Use a separate flow to handle each API call
            callbackFlow {
                val response = client.async().chat().completions().createStreaming(parameterConverter.prepareCompletion(currentParams))

                val functionCallAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
                val textAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
                val responseOutputItemAccumulator = mutableListOf<ResponseOutputItem>()
                val internalToolItemIds = mutableSetOf<String>()
                val functionNameAccumulator = mutableMapOf<Long, Pair<String, String>>()

                // Send initial created event only for the first iteration

                val subscription = response.subscribe { completion ->
                    if (!completion._choices().isMissing()) {
                        // Send in progress event if not already sent
                        if (!inProgressEventFired) {
                            trySend(
                                EventUtils.convertEvent(
                                    ResponseStreamEvent.ofCreated(
                                        ResponseCreatedEvent.builder()
                                            .response(
                                                ChatCompletionConverter.buildIntermediateResponse(
                                                    currentParams,
                                                    ResponseStatus.IN_PROGRESS,
                                                    responseId
                                                )
                                            )
                                            .build()
                                    )
                                )
                            ).isSuccess

                            trySend(
                                EventUtils.convertEvent(
                                    ResponseStreamEvent.ofInProgress(
                                        ResponseInProgressEvent.builder()
                                            .response(
                                                ChatCompletionConverter.buildIntermediateResponse(
                                                    currentParams,
                                                    ResponseStatus.IN_PROGRESS,
                                                    responseId
                                                )
                                            )
                                            .build()
                                    )
                                )
                            ).isSuccess
                            inProgressEventFired = true
                        }

                        // Handle normal completion with stop, length, or content_filter finish reason
                        if (completion.choices()
                                .any { it.finishReason().isPresent && (it.finishReason().get().asString() == "stop"
                                        || it.finishReason().get().asString() == "length"
                                        || it.finishReason().get().asString() == "content_filter") }) {
                            
                            // Process all accumulated text
                            handleTextCompletion(textAccumulator, responseOutputItemAccumulator)

                            // Signal completion
                            nextIteration = false

                            // Handle different types of completion
                            if(completion.choices().any{it.finishReason().get().asString() == "stop"}) {
                                // Send completed event
                                val finalResponse = ChatCompletionConverter.buildFinalResponse(
                                    currentParams,
                                    ResponseStatus.COMPLETED,
                                    responseId,
                                    responseOutputItemAccumulator
                                )

                                trySend(
                                    EventUtils.convertEvent(
                                        ResponseStreamEvent.ofCompleted(
                                            ResponseCompletedEvent.builder()
                                                .response(finalResponse)
                                                .build()
                                        )
                                    )
                                ).isSuccess
                            } else {
                                // Handle incomplete response (length or content filter)
                                val finalResponse = ChatCompletionConverter.buildFinalResponse(
                                    currentParams,
                                    ResponseStatus.INCOMPLETE,
                                    responseId,
                                    responseOutputItemAccumulator,
                                    if(completion.choices().any{it.finishReason().get().asString() == "length"}) 
                                        Response.IncompleteDetails.builder().reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS).build()
                                    else 
                                        Response.IncompleteDetails.builder().reason(Response.IncompleteDetails.Reason.CONTENT_FILTER).build()
                                )
                                trySend(
                                    EventUtils.convertEvent(
                                        ResponseStreamEvent.ofIncomplete(
                                            ResponseIncompleteEvent.builder()
                                                .response(finalResponse)
                                                .build()
                                        )
                                    )
                                ).isSuccess
                            }
                        } else {
                            // Process ongoing completion
                            convertAndPublish(
                                completion,
                                functionCallAccumulator,
                                textAccumulator,
                                responseOutputItemAccumulator,
                                internalToolItemIds,
                                functionNameAccumulator
                            )

                            // Check for tool calls
                            if (completion.choices().any {
                                    it.finishReason().isPresent && it.finishReason().get().asString() == "tool_calls"
                                }) {

                                // Process accumulated text
                                handleTextCompletion(textAccumulator, responseOutputItemAccumulator, true)

                                val response = ChatCompletionConverter.buildFinalResponse(
                                    currentParams,
                                    ResponseStatus.COMPLETED,
                                    responseId,
                                    responseOutputItemAccumulator
                                )

                                if (internalToolItemIds.isEmpty()) {
                                    // No internal tools to process, finish
                                    nextIteration = false
                                    trySend(
                                        EventUtils.convertEvent(
                                            ResponseStreamEvent.ofCompleted(
                                                ResponseCompletedEvent.builder()
                                                    .response(response)
                                                    .build()
                                            )
                                        )
                                    ).isSuccess
                                } else {
                                    // Handle tool calls and prepare for next iteration
                                    val toolResponseItems = toolHandler.handleMasaicToolCall(currentParams, response)
                                    // Update params for the next iteration
                                    currentParams = currentParams.toBuilder()
                                        .input(ResponseCreateParams.Input.ofResponse(toolResponseItems))
                                        .build()

                                    // Signal to continue the outer loop with new params
                                    nextIteration = true

                                    // Close this inner flow to move to next iteration
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
                // Emit all events from the inner flow to the outer flow
                emit(event)
            }

            // Update shouldContinue based on the result of this iteration
            shouldContinue = nextIteration
        }
    }

    /**
     * Processes the accumulated text and sends appropriate events.
     *
     * @param textAccumulator The accumulated text chunks
     * @param responseOutputItemAccumulator The accumulated output items
     */
    private fun ProducerScope<ServerSentEvent<String>>.handleTextCompletion(
        textAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        prepend: Boolean = false
    ) {
        if(textAccumulator.isNotEmpty()) {
            // Send text done events
            textAccumulator.forEach {
                val content = it.value.joinToString("") { it.asOutputTextDelta().delta() }

                trySend(
                    EventUtils.convertEvent(
                        ResponseStreamEvent.ofOutputTextDone(
                            ResponseTextDoneEvent.builder()
                                .contentIndex(it.key)
                                .text(content)
                                .outputIndex(it.key)
                                .itemId(it.value.first().asOutputTextDelta().itemId())
                                .build()
                        )
                    )
                ).isSuccess
            }


            // Add the text message to output items
            if (prepend) {
                responseOutputItemAccumulator.addFirst(
                    ResponseOutputItem.ofMessage(
                        ResponseOutputMessage.builder()
                            .content(textAccumulator.map {
                                ResponseOutputMessage.Content.ofOutputText(
                                    ResponseOutputText.builder()
                                        .text(it.value.joinToString("") { it.asOutputTextDelta().delta() })
                                        .annotations(listOf())
                                        .build()
                                )
                            }
                            ).id(UUID.randomUUID().toString())
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .role(JsonValue.from("assistant"))
                            .build()
                    ))
            } else {
                responseOutputItemAccumulator.add(
                    ResponseOutputItem.ofMessage(
                        ResponseOutputMessage.builder()
                            .content(textAccumulator.map {
                                ResponseOutputMessage.Content.ofOutputText(
                                    ResponseOutputText.builder()
                                        .text(it.value.joinToString("") { it.asOutputTextDelta().delta() })
                                        .annotations(listOf())
                                        .build()
                                )
                            }
                            ).id(UUID.randomUUID().toString())
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .role(JsonValue.from("assistant"))
                            .build()
                    ))
            }
        }
    }

    /**
     * Processes a completion chunk and publishes appropriate events.
     *
     * @param completion The completion chunk to process
     * @param functionCallAccumulator Accumulator for function call events
     * @param textAccumulator Accumulator for text events
     * @param responseOutputItemAccumulator Accumulator for output items
     * @param internalToolItemIds Set of internal tool IDs
     * @param functionNameAccumulator Accumulator for function names
     */
    private fun ProducerScope<ServerSentEvent<String>>.convertAndPublish(
        completion: ChatCompletionChunk,
        functionCallAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        textAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        internalToolItemIds: MutableSet<String>,
        functionNameAccumulator: MutableMap<Long, Pair<String, String>>
    ) {
        completion.toResponseStreamEvent().let { event ->
            event.forEach {
                when {
                    it.isFunctionCallArgumentsDelta() -> {
                        handleFunctionCallDelta(it, functionCallAccumulator, internalToolItemIds, completion)
                    }
                    it.isOutputItemAdded() && it.asOutputItemAdded().item().isFunctionCall() -> {
                        handleOutputItemAdded(it, functionNameAccumulator,responseOutputItemAccumulator, internalToolItemIds)
                    }
                    it.isOutputTextDelta() -> {
                        handleOutputTextDelta(it, textAccumulator)
                    }
                    it.isFunctionCallArgumentsDone() -> {
                        handleFunctionCallDone(functionCallAccumulator, functionNameAccumulator, responseOutputItemAccumulator, internalToolItemIds, completion)
                    }
                    it.isOutputItemDone() -> {
                        responseOutputItemAccumulator.add(it.asOutputItemDone().item())
                        trySend(EventUtils.convertEvent(it)).isSuccess
                    }
                    else -> {
                        trySend(EventUtils.convertEvent(it)).isSuccess
                    }
                }
            }
        }
    }

    /**
     * Handles function call delta events.
     */
    private fun ProducerScope<ServerSentEvent<String>>.handleFunctionCallDelta(
        event: ResponseStreamEvent,
        functionCallAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        internalToolItemIds: MutableSet<String>,
        completion: ChatCompletionChunk
    ) {
        functionCallAccumulator.getOrPut(event.asFunctionCallArgumentsDelta().outputIndex()) {
            mutableListOf()
        }.add(event)
        
        if (!internalToolItemIds.contains(completion.id())) {
            trySend(EventUtils.convertEvent(event)).isSuccess
        }
    }

    /**
     * Handles output item added events.
     */
    private fun ProducerScope<ServerSentEvent<String>>.handleOutputItemAdded(
        event: ResponseStreamEvent,
        functionNameAccumulator: MutableMap<Long, Pair<String, String>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        internalToolItemIds: MutableSet<String>
    ) {
        val functionCall = event.asOutputItemAdded().item().asFunctionCall()
        val functionName = functionCall.name()
        functionNameAccumulator[event.asOutputItemAdded().outputIndex()] =
            Pair(functionName, functionCall.callId())

        if(toolService.getFunctionTool(functionName) != null) {
            internalToolItemIds.add(functionCall.id())
        }

        if(functionCall.arguments().isNotBlank()){ //assuming full argument is present. For e.g. in case of groq
            responseOutputItemAccumulator.add(
                ResponseOutputItem.ofFunctionCall(
                    ResponseFunctionToolCall.builder()
                        .name(functionName)
                        .arguments(functionCall.arguments())
                        .callId(functionCall.callId())
                        .id(functionCall.id())
                        .status(ResponseFunctionToolCall.Status.COMPLETED)
                        .putAllAdditionalProperties(functionCall._additionalProperties())
                        .type(JsonValue.from("function_call"))
                        .build()
                )
            )
        }

        trySend(EventUtils.convertEvent(event)).isSuccess
    }

    /**
     * Handles output text delta events.
     */
    private fun ProducerScope<ServerSentEvent<String>>.handleOutputTextDelta(
        event: ResponseStreamEvent,
        textAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>
    ) {
        textAccumulator.getOrPut(event.asOutputTextDelta().outputIndex()) {
            mutableListOf()
        }.add(event)
        
        trySend(EventUtils.convertEvent(event)).isSuccess
    }

    /**
     * Handles function call done events.
     */
    private fun ProducerScope<ServerSentEvent<String>>.handleFunctionCallDone(
        functionCallAccumulator: MutableMap<Long, MutableList<ResponseStreamEvent>>,
        functionNameAccumulator: MutableMap<Long, Pair<String, String>>,
        responseOutputItemAccumulator: MutableList<ResponseOutputItem>,
        internalToolItemIds: MutableSet<String>,
        completion: ChatCompletionChunk
    ) {
        functionCallAccumulator.forEach { key, value ->
            val content = value.joinToString("") { it.asFunctionCallArgumentsDelta().delta() }

            if (!internalToolItemIds.contains(completion.id())) {
                trySend(
                    EventUtils.convertEvent(
                        ResponseStreamEvent.ofFunctionCallArgumentsDone(
                            ResponseFunctionCallArgumentsDoneEvent.builder()
                                .outputIndex(key)
                                .arguments(content)
                                .itemId(value.first().asFunctionCallArgumentsDelta().itemId())
                                .putAllAdditionalProperties(
                                    value.first().asFunctionCallArgumentsDelta()._additionalProperties()
                                )
                                .build()
                        )
                    )
                ).isSuccess
            }

            if(!responseOutputItemAccumulator.filter { it.isFunctionCall() }.any { it.asFunctionCall().name() == functionNameAccumulator.getValue(key).first }) {
                responseOutputItemAccumulator.add(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                            .name(functionNameAccumulator.getValue(key).first)
                            .arguments(content)
                            .callId(functionNameAccumulator.getValue(key).second)
                            .id(value.first().asFunctionCallArgumentsDelta().itemId())
                            .status(ResponseFunctionToolCall.Status.COMPLETED)
                            .putAllAdditionalProperties(
                                value.first().asFunctionCallArgumentsDelta()._additionalProperties()
                            )
                            .type(JsonValue.from("function_call"))
                            .build()
                    )
                )
            }
        }
    }
} 