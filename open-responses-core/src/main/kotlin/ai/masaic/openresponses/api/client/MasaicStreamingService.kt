package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.EventUtils
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.tool.ToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.*
import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactor.ReactorContext
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Service
class MasaicStreamingService(
    private val toolHandler: MasaicToolHandler,
    private val parameterConverter: MasaicParameterConverter,
    private val toolService: ToolService,
    private val responseStore: ResponseStore,
    // Make these constructor params for easy mocking:
    private val allowedMaxToolCalls: Int = System.getenv("OPEN_RESPONSES_MAX_TOOL_CALLS")?.toInt() ?: 30,
    private val maxDuration: Long = System.getenv("OPEN_RESPONSES_MAX_STREAMING_TIMEOUT")?.toLong() ?: 300000L, // 300 seconds
    private val payloadFormatter: PayloadFormatter,
    private val objectMapper: ObjectMapper,
    private val telemetryService: TelemetryService,
) {
    private val logger = KotlinLogging.logger {}

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
        metadata: InstrumentationMetadataInput,
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
                    "Too many tool calls. Increase the limit by setting OPEN_RESPONSES_MAX_TOOL_CALLS environment variable.",
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
                        metadata,
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
        metadata: InstrumentationMetadataInput,
    ): IterationResult {
        var nextIteration = false
        var updatedParams = params
        var inProgressFired = alreadyInProgressEventFired

        // We'll collect SSE events from the streaming call:
        val genAiSample = telemetryService.genAiDurationSample()
        val sseFlow =
            channelFlow {
                // Link the 'chat' span to any existing HTTP span from Reactor context
                val parentObs: Observation? =
                    coroutineContext[ReactorContext]?.context?.get(ObservationThreadLocalAccessor.KEY)
                val observation = telemetryService.startObservation("chat", metadata.modelName, parentObs)
                val createParams = parameterConverter.prepareCompletion(params)
                telemetryService.emitModelInputEvents(observation, createParams, metadata)
                val functionCallAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
                val textAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
                val responseOutputItemAccumulator = mutableListOf<ResponseOutputItem>()
                val internalToolItemIds = mutableSetOf<String>()
                val functionNameAccumulator = mutableMapOf<Long, Pair<String, String>>()

                client.streamCompletions(createParams).collect { it ->
                    val completion =
                        if (it._id().isMissing()) { // special handling for gemini
                            val builder = it.toBuilder()
                            builder.id(UUID.randomUUID().toString())
                            builder.build()
                        } else {
                            it
                        }

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
                                            ).sequenceNumber(System.nanoTime())
                                            .build(),
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
                            if (!completion._choices().isMissing()) {
                                completion.choices().mapIndexed { index, choice ->
                                    choice.delta().content().ifPresent {
                                        textAccumulator
                                            .getOrPut(choice.index()) { mutableListOf() }
                                            .add(
                                                ResponseStreamEvent.ofOutputTextDelta(
                                                    ResponseTextDeltaEvent
                                                        .builder()
                                                        .delta(it)
                                                        .outputIndex(choice.index())
                                                        .contentIndex(index.toLong())
                                                        .itemId(completion.id())
                                                        .putAllAdditionalProperties(choice._additionalProperties())
                                                        .sequenceNumber(System.nanoTime())
                                                        .build(),
                                                ),
                                            )
                                    }
                                }
                            }

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

                                    // Store the response in the response store
                                    storeResponseWithInputItems(finalResponse, params)

                                    nextIteration = false
                                    trySend(
                                        EventUtils.convertEvent(
                                            ResponseStreamEvent.ofCompleted(
                                                ResponseCompletedEvent
                                                    .builder()
                                                    .response(finalResponse)
                                                    .sequenceNumber(System.nanoTime())
                                                    .build(),
                                            ),
                                            payloadFormatter,
                                            objectMapper,
                                        ),
                                    )

                                    logger.debug { "Response body: ${objectMapper.writeValueAsString(finalResponse)}" }
                                    telemetryService.stopObservation(observation, finalResponse, params, metadata)
                                    telemetryService.stopGenAiDurationSample(metadata, params, genAiSample)
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
                                    // Store the incomplete response in the response store
                                    storeResponseWithInputItems(finalResponse, params)
                                    trySend(
                                        EventUtils.convertEvent(
                                            ResponseStreamEvent.ofIncomplete(
                                                ResponseIncompleteEvent
                                                    .builder()
                                                    .response(finalResponse)
                                                    .sequenceNumber(System.nanoTime())
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
                                params,
                            )

                            // If we detect tool_calls:
                            if (completion.choices().any { choice ->
                                    choice.finishReason().isPresent &&
                                        choice
                                            .finishReason()
                                            .get()
                                            .asString() == "tool_calls"
                                }
                            ) {
                                // Process text so far, put it at the beginning
                                handleTextCompletion(textAccumulator, responseOutputItemAccumulator, prepend = true)

                                // responseOutputItemAccumulator now contains all text and ResponseOutputItem.ofFunctionCall items
                                // from the convertAndPublish method for this LLM response.
                                val responseWithToolRequests =
                                    ChatCompletionConverter.buildFinalResponse(
                                        params,
                                        ResponseStatus.COMPLETED, // LLM's turn is complete, it requested tools.
                                        responseId,
                                        responseOutputItemAccumulator, // Contains text and tool call requests from LLM
                                    )

                                logger.debug {
                                    "Response body (LLM requesting tools): ${
                                        objectMapper.writeValueAsString(
                                            responseWithToolRequests,
                                        )
                                    }"
                                }
                                telemetryService.stopObservation(observation, responseWithToolRequests, params, metadata)
                                telemetryService.stopGenAiDurationSample(metadata, params, genAiSample)

                                // internalToolItemIds is populated by convertAndPublish if a tool call matches a known internal tool.
                                if (internalToolItemIds.isEmpty()) {
                                    storeResponseWithInputItems(responseWithToolRequests, params)
                                    // LLM requested tools, but none were recognized as internal/actionable by us.
                                    logger.info { "Response completed with tool requests, but no recognized internal tools to execute. ID: ${responseWithToolRequests.id()}" }
                                    nextIteration = false
                                    trySend(
                                        EventUtils.convertEvent(
                                            ResponseStreamEvent.ofCompleted(
                                                ResponseCompletedEvent
                                                    .builder()
                                                    .response(responseWithToolRequests) // Send the response that includes the tool requests
                                                    .sequenceNumber(System.nanoTime())
                                                    .build(),
                                            ),
                                            payloadFormatter,
                                            objectMapper,
                                        ),
                                    ).isSuccess // Or handle failure
                                    close() // Close to terminate this iteration of callbackFlow
                                } else {
                                    // Recognized internal tools were requested, proceed to handle them.
                                    val parentObservation =
                                        coroutineContext[ReactorContext]?.context?.get<Observation>(
                                            ObservationThreadLocalAccessor.KEY,
                                        )

                                    val toolStreamingResult =
                                        toolHandler.handleMasaicToolCall(
                                            params = params, // The original ResponseCreateParams for this iteration
                                            response = responseWithToolRequests, // The Response from LLM containing tool requests
                                            eventEmitter = { event -> trySend(event).isSuccess },
                                            parentObservation = parentObservation,
                                            openAIClient = client,
                                        )

                                    if (toolStreamingResult.shouldTerminate && toolStreamingResult.terminalOutputItem != null) {
                                        // A terminal tool (e.g., image_generation) was executed.
                                        logger.info { "Terminal tool executed in stream. Completing stream with tool output." }

                                        val finalTerminalResponse =
                                            ChatCompletionConverter.buildFinalResponse(
                                                params,
                                                ResponseStatus.COMPLETED, // LLM's turn is complete, it requested tools.
                                                responseId,
                                                listOf(toolStreamingResult.terminalOutputItem), // Contains text and tool call requests from LLM
                                            )

                                        storeResponseWithInputItems(finalTerminalResponse, params)

                                        trySend(
                                            EventUtils.convertEvent(
                                                ResponseStreamEvent.ofCompleted(
                                                    ResponseCompletedEvent
                                                        .builder()
                                                        .response(finalTerminalResponse)
                                                        .sequenceNumber(System.nanoTime())
                                                        .build(),
                                                ),
                                                payloadFormatter,
                                                objectMapper,
                                            ),
                                        ).isSuccess
                                        nextIteration = false
                                        close()
                                    } else {
                                        // Non-terminal tools were executed, or no specific terminal output to send directly.
                                        // Prepare for the next LLM call with the tool outputs.
                                        updatedParams =
                                            params
                                                .toBuilder()
                                                .input(ResponseCreateParams.Input.ofResponse(toolStreamingResult.toolResponseItems))
                                                .build()

                                        // We'll do another iteration if there are tool responses to send to the LLM.
                                        // The toolResponseItems should contain the necessary data for the next call.
                                        nextIteration = true
                                        close() // Close to proceed to the next iteration of the outer loop.
                                    }
                                }
                            }
                        }
                    }
                }
            }

        sseFlow.collect { event -> emit(event) }

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

    private fun OpenAIClient.streamCompletions(params: ChatCompletionCreateParams): kotlinx.coroutines.flow.Flow<ChatCompletionChunk> =
        callbackFlow {
            val subscription = async().chat().completions().createStreaming(params)

            subscription.onCompleteFuture().whenComplete { _, error ->
                if (error != null) {
                    close(error)
                }
            }
            // forward onNext
            subscription.subscribe { completion -> trySend(completion).isSuccess }

            subscription.onCompleteFuture().whenComplete { _, err ->
                if (err != null) close(err) else close()
            }
            // forward terminal states
            awaitClose {
                try {
                    close() // or cancel() if available
                } catch (_: Throwable) {
                }
            }
        }

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
                        ).sequenceNumber(System.nanoTime())
                        .build(),
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
                            "Too many tool calls. Increase the limit by setting OPEN_RESPONSES_MAX_TOOL_CALLS environment variable.",
                        ).code("too_many_tool_calls")
                        .param(null)
                        .sequenceNumber(System.nanoTime())
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
                            "Timeout while processing. Increase the timeout limit by setting OPEN_RESPONSES_MAX_STREAMING_TIMEOUT environment variable.",
                        ).code("timeout")
                        .param(null)
                        .type(JsonValue.from("response.error"))
                        .sequenceNumber(System.nanoTime())
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
                                .sequenceNumber(System.nanoTime())
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
        params: ResponseCreateParams,
    ) {
        completion.toResponseStreamEvent().forEach { event ->
            when {
                event.isFunctionCallArgumentsDelta() -> {
                    handleFunctionCallDelta(event, functionCallAccumulator, internalToolItemIds, completion)
                }
                event.isOutputItemAdded() && event.asOutputItemAdded().item().isFunctionCall() -> {
                    handleOutputItemAdded(event, functionNameAccumulator, responseOutputItemAccumulator, internalToolItemIds, params)
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
        params: ResponseCreateParams,
    ) {
        val functionCall = event.asOutputItemAdded().item().asFunctionCall()
        val functionName = functionCall.name()
        val outputIndex = event.asOutputItemAdded().outputIndex()

        functionNameAccumulator[outputIndex] = Pair(functionName, functionCall.callId())

        // Create context with alias mappings
        val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
        val context = ToolRequestContext(aliasMap, params)

        // If a recognized function, mark internal
        if (toolService.getFunctionTool(functionName, context) != null) {
            internalToolItemIds.add(functionCall.id().getOrNull() ?: functionCall.callId())
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
                        .id(functionCall.id().getOrNull() ?: functionCall.callId())
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
                                .sequenceNumber(System.nanoTime())
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

    /**
     * Helper method to store a response and its input items in the response store.
     */
    private suspend fun storeResponseWithInputItems(
        response: Response,
        params: ResponseCreateParams,
    ) {
        if (params.store().isPresent && params.store().get()) {
            val inputItems =
                if (params.input().isResponse()) {
                    params.input().asResponse()
                } else {
                    listOf(
                        ResponseInputItem.ofEasyInputMessage(
                            EasyInputMessage
                                .builder()
                                .content(params.input().asText())
                                .role(EasyInputMessage.Role.USER)
                                .build(),
                        ),
                    )
                }

            // Create context with alias mappings
            val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
            val context = ToolRequestContext(aliasMap, params)

            responseStore.storeResponse(response, inputItems, context)
            logger.debug { "Stored response with ID: ${response.id()} and ${inputItems.size} input items" }
        }
    }
}
