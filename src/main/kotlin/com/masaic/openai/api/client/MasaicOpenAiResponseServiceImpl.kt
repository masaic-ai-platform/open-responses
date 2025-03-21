package com.masaic.openai.api.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.masaic.openai.api.utils.EventUtils
import com.masaic.openai.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.FunctionDefinition
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.*
import com.openai.models.responses.*
import com.openai.models.responses.ResponseInputItem.FunctionCallOutput
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.springframework.http.codec.ServerSentEvent
import java.util.UUID

/**
 * Implementation of ResponseService for Masaic OpenAI API client.
 * This service handles communication with OpenAI's API for chat completions
 * and provides methods to create, retrieve, and stream responses.
 *
 * @param client The OpenAI client used to make API requests
 */
class MasaicOpenAiResponseServiceImpl(
    private val client: OpenAIClient,
    private val toolService: ToolService
) : ResponseService {

    val objectMapper = jacksonObjectMapper()

    val allowedMaxToolCalls = System.getenv("MASAIC_MAX_TOOL_CALLS")?.toInt() ?: 10
    val maxDuration: Long =
        System.getenv("MASAIC_MAX_STREAMING_TIMEOUT")?.toLong() ?: 60000L // 60 seconds max total processing time

    /**
     * Not implemented: Returns a version of this service that includes raw HTTP response data.
     */
    override fun withRawResponse(): ResponseService.WithRawResponse {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Returns the input items service for this response service.
     */
    override fun inputItems(): InputItemService {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Creates a new completion response based on provided parameters.
     *
     * @param params Parameters for creating the response
     * @param requestOptions Options for the HTTP request
     * @return Response object containing completion data
     */
    override fun create(
        params: ResponseCreateParams,
        requestOptions: RequestOptions
    ): Response {
        val chatCompletions = client.chat().completions().create(prepareCompletion(params))
        val responseInputItems = handleMasaicToolCall(chatCompletions, params)
        if (responseInputItems.filter { it.isFunctionCall() }.size > responseInputItems.filter { it.isFunctionCallOutput() }.size) {
            return chatCompletions.toResponse(params)
        } else if (responseInputItems.filter { it.isFunctionCall() }.size > allowedMaxToolCalls) {
            throw IllegalArgumentException("Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable.")
        }

        // Rebuild the params with the updated input items.
        val newParams = params.toBuilder()
            .input(ResponseCreateParams.Input.ofResponse(responseInputItems))
            .build()

        // Recreate the chat completions with the updated params.
        return create(newParams, requestOptions)
    }

    fun handleMasaicToolCall(chatCompletion: ChatCompletion, params: ResponseCreateParams): List<ResponseInputItem> {

        val responseInputItems =
            if (params.input().isResponse()) params.input().asResponse().toMutableList() else mutableListOf(
                ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder().content(params.input().asText()).role(EasyInputMessage.Role.USER)
                        .build()
                )
            )
        val parked = mutableListOf<ResponseInputItem>()

        chatCompletion.choices().filter { it.message().content().isPresent && it.message().content().get().isNotBlank() }.forEach {
            parked.add(ResponseInputItem.ofResponseOutputMessage(
                ResponseOutputMessage.builder().content(
                    listOf(
                        ResponseOutputMessage.Content.ofOutputText(
                            ResponseOutputText.builder().text(it.message().content().get()).annotations(listOf()).build()
                        )
                    )
                ).id(UUID.randomUUID().toString()).role(JsonValue.from("assistant")).status(ResponseOutputMessage.Status.COMPLETED).build()
            ))
        }

        chatCompletion.choices().forEach { chatChoice ->

            if (ChatCompletion.Choice.FinishReason.TOOL_CALLS == chatChoice.finishReason()) {
                val message = chatChoice.message()

                message.toolCalls().get().forEach { tool ->
                    val function = tool.function()
                    if(toolService.getFunctionTool(function.name()) != null) {
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall.builder().callId(tool.id())
                                    .id(tool.id())
                                    .name(function.name()).arguments(function.arguments()).build()
                            )
                        )
                        val toolResult =
                            toolService.executeTool(function.name(), function.arguments()) //TODO: JB Handle exceptions
                        toolResult?.let {
                            responseInputItems.add(
                                ResponseInputItem.ofFunctionCallOutput(
                                    FunctionCallOutput.builder().callId(tool.id())
                                        .id(tool.id())
                                        .output(toolResult).build()
                                )
                            )
                        }
                    }
                    else {
                        parked.add(
                            ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall.builder().callId(tool.id())
                                    .id(tool.id())
                                    .name(function.name()).arguments(function.arguments()).build()
                            )
                        )
                    }
                }
            }
        }
        responseInputItems.addAll(parked) // Add parked tools to the end for client to handle

        return responseInputItems
    }

    fun handleMasaicToolCall(params: ResponseCreateParams, response: Response): List<ResponseInputItem> {

        val responseInputItems =
            if (params.input().isResponse()) params.input().asResponse().toMutableList() else mutableListOf(
                ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder().content(params.input().asText()).role(EasyInputMessage.Role.USER).build()
                )
            )

        val parked = mutableListOf<ResponseInputItem>()

        response.output().filter { it.isMessage() && it.message().get().content().isNotEmpty() }.forEach{ parked.add(ResponseInputItem.ofResponseOutputMessage(it.asMessage())) }

        response.output().filter {
            it.isFunctionCall()
        }.forEachIndexed { index, tool ->
            val function = tool.asFunctionCall()

            if(toolService.getFunctionTool(function.name()) != null) {
                responseInputItems.add(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder().callId(function.callId())
                            .id(function.id())
                            .name(function.name()).arguments(function.arguments()).build()
                    )
                )

                val toolResult =
                    toolService.executeTool(function.name(), function.arguments()) //TODO: JB Handle exceptions
                toolResult?.let {
                    responseInputItems.add(
                        ResponseInputItem.ofFunctionCallOutput(
                            FunctionCallOutput.builder().callId(function.callId())
                                .id(function.id())
                                .output(toolResult).build()
                        )
                    )
                }
            }
            else {
                parked.add(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder().callId(function.callId())
                            .id(function.id())
                            .name(function.name()).arguments(function.arguments()).build()
                    )
                )
            }
        }

        responseInputItems.addAll(parked) // Add parked tools to the end for client to handle
        return responseInputItems
    }

    /**
     * Not implemented: Creates a streaming response.
     */
    override fun createStreaming(
        params: ResponseCreateParams,
        requestOptions: RequestOptions
    ): StreamResponse<ResponseStreamEvent> {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Retrieves a specific response by ID.
     */
    override fun retrieve(
        params: ResponseRetrieveParams,
        requestOptions: RequestOptions
    ): Response {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Deletes a response by ID.
     */
    override fun delete(
        params: ResponseDeleteParams,
        requestOptions: RequestOptions
    ) {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Creates a streaming completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     *
     * @param initialParams Parameters for creating the completion
     * @return Flow of ServerSentEvents containing response chunks
     */
    fun createCompletionStream(
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
                val response = client.async().chat().completions().createStreaming(prepareCompletion(currentParams))

                val functionCallAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
                val textAccumulator = mutableMapOf<Long, MutableList<ResponseStreamEvent>>()
                val responseOutputItemAccumulator = mutableListOf<ResponseOutputItem>()
                val internalToolItemIds = mutableSetOf<String>()
                val functionNameAccumulator = mutableMapOf<Long, Pair<String, String>>()

                // Send initial created event only for the first iteration
                if (currentParams == initialParams) {
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
                }

                val subscription = response.subscribe { completion ->

                    if (!completion._choices().isMissing()) {
                        // Send in progress event if not already sent
                        if (!inProgressEventFired) {
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

                        if (completion.choices()
                                .any { it.finishReason().isPresent && it.finishReason().get().asString() == "stop" }
                        ) {
                            // Handle normal completion
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

                            responseOutputItemAccumulator.add(
                                ResponseOutputItem.ofMessage(
                                    ResponseOutputMessage.builder()
                                        .content(textAccumulator.map {
                                            ResponseOutputMessage.Content.ofOutputText(
                                                ResponseOutputText.builder()
                                                    .text(it.value.joinToString("") { it.asOutputTextDelta().delta() })
                                                    .annotations(
                                                        listOf()
                                                    ).build()
                                            )
                                        }
                                        ).id(
                                            UUID.randomUUID().toString()
                                        )
                                        .status(ResponseOutputMessage.Status.COMPLETED)
                                        .role(JsonValue.from("assistant"))
                                        .build()
                                ))

                            // Signal completion - explicitly set to false
                            nextIteration = false

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

                                // Handle normal completion
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

                                responseOutputItemAccumulator.addFirst(
                                    ResponseOutputItem.ofMessage(
                                        ResponseOutputMessage.builder()
                                            .content(textAccumulator.map {
                                                ResponseOutputMessage.Content.ofOutputText(
                                                    ResponseOutputText.builder()
                                                        .text(it.value.joinToString("") { it.asOutputTextDelta().delta() })
                                                        .annotations(
                                                            listOf()
                                                        ).build()
                                                )
                                            }
                                            ).id(
                                                UUID.randomUUID().toString()
                                            )
                                            .status(ResponseOutputMessage.Status.COMPLETED)
                                            .role(JsonValue.from("assistant"))
                                            .build()
                                    ))

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
                                    val toolResponseItems = handleMasaicToolCall(currentParams, response)
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
                if (it.isFunctionCallArgumentsDelta()) {
                    functionCallAccumulator.getOrPut(it.asFunctionCallArgumentsDelta().outputIndex()) {
                        mutableListOf()
                    }.add(it)
                    if (!internalToolItemIds.contains(completion.id()))
                        trySend(EventUtils.convertEvent(it)).isSuccess
                } else if (it.isOutputItemAdded() && it.asOutputItemAdded().item().isFunctionCall()) {
                    val functionName = it.asOutputItemAdded().item().asFunctionCall().name()
                    functionNameAccumulator[it.asOutputItemAdded().outputIndex()] =
                        Pair(functionName, it.asOutputItemAdded().item().asFunctionCall().callId())
                    if (toolService.getFunctionTool(functionName) != null) {
                        internalToolItemIds.add(completion.id())
                    }
                    trySend(EventUtils.convertEvent(it)).isSuccess
                } else if (it.isOutputTextDelta()) {
                    textAccumulator.getOrPut(it.asOutputTextDelta().outputIndex()) {
                        mutableListOf()
                    }.add(it)
                    trySend(EventUtils.convertEvent(it)).isSuccess
                } else if (it.isFunctionCallArgumentsDone()) {
                    functionCallAccumulator.forEach {
                        val content = it.value.joinToString("") { it.asFunctionCallArgumentsDelta().delta() }

                        if (!internalToolItemIds.contains(completion.id())) {
                            trySend(
                                EventUtils.convertEvent(
                                    ResponseStreamEvent.ofFunctionCallArgumentsDone(
                                        ResponseFunctionCallArgumentsDoneEvent.builder()
                                            .outputIndex(it.key)
                                            .arguments(content)
                                            .itemId(it.value.first().asFunctionCallArgumentsDelta().itemId())
                                            .putAllAdditionalProperties(
                                                it.value.first().asFunctionCallArgumentsDelta()._additionalProperties()
                                            )
                                            .build()
                                    )
                                )
                            ).isSuccess
                        }

                        responseOutputItemAccumulator.add(
                            ResponseOutputItem.ofFunctionCall(
                                ResponseFunctionToolCall.builder()
                                    .name(functionNameAccumulator.getValue(it.key).first)
                                    .arguments(content)
                                    .callId(functionNameAccumulator.getValue(it.key).second)
                                    .id(it.value.first().asFunctionCallArgumentsDelta().itemId())
                                    .status(ResponseFunctionToolCall.Status.COMPLETED)
                                    .putAllAdditionalProperties(
                                        it.value.first().asFunctionCallArgumentsDelta()._additionalProperties()
                                    )
                                    .type(JsonValue.from("function_call"))
                                    .build()
                            )
                        )
                    }
                } else if (it.isOutputItemDone()) {
                    responseOutputItemAccumulator.add(it.asOutputItemDone().item())
                    trySend(EventUtils.convertEvent(it)).isSuccess
                } else {
                    trySend(EventUtils.convertEvent(it)).isSuccess
                }
            }
        }
    }

    /**
     * Prepares a chat completion request from response parameters.
     * This is the main function that transforms ResponseCreateParams into ChatCompletionCreateParams.
     *
     * @param params Parameters for creating the response
     * @return ChatCompletionCreateParams object ready to send to OpenAI API
     */
    private fun prepareCompletion(
        params: ResponseCreateParams
    ): ChatCompletionCreateParams {
        val input = params.input()
        val completionRequest = createBaseCompletionRequest(input)

        applyModelAndParameters(completionRequest, params)
        applyToolConfiguration(completionRequest, params)
        applyResponseFormatting(completionRequest, params)
        applyReasoningEffort(completionRequest, params)

        return completionRequest.build()
    }

    /**
     * Creates the base completion request with messages.
     *
     * @param input The input from response parameters
     * @return Builder for ChatCompletionCreateParams with messages set
     */
    private fun createBaseCompletionRequest(input: ResponseCreateParams.Input): ChatCompletionCreateParams.Builder {
        return if (input.isText()) {
            createTextBasedRequest(input)
        } else {
            createMessageBasedRequest(input)
        }
    }

    /**
     * Creates a completion request for simple text input.
     *
     * @param input The text input
     * @return Builder with a single user message
     */
    private fun createTextBasedRequest(input: ResponseCreateParams.Input): ChatCompletionCreateParams.Builder {
        return ChatCompletionCreateParams.builder().addMessage(
            ChatCompletionUserMessageParam.builder().content(input.toString()).build()
        )
    }

    /**
     * Creates a completion request for complex message-based input.
     *
     * @param input The input containing multiple messages
     * @return Builder with all messages properly converted and added
     */
    private fun createMessageBasedRequest(input: ResponseCreateParams.Input): ChatCompletionCreateParams.Builder {
        val inputItems = input.asResponse()
        val completionBuilder = ChatCompletionCreateParams.builder()

        inputItems.forEach { item ->
            when {
                item.isEasyInputMessage() || item.isMessage() || item.isResponseOutputMessage() -> {
                    convertInputMessages(item, completionBuilder)
                }

                item.isFunctionCall() -> {
                    addFunctionCallMessage(item, completionBuilder)
                }

                item.isFunctionCallOutput() -> {
                    addFunctionCallOutputMessage(item, completionBuilder)
                }
            }
        }

        return completionBuilder
    }

    /**
     * Adds a function call message to the completion request.
     *
     * @param item The function call input item
     * @param completionBuilder The builder to add the message to
     */
    private fun addFunctionCallMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        val functionCall = item.asFunctionCall()
        completionBuilder.addMessage(
            ChatCompletionAssistantMessageParam.builder()
                .addToolCall(
                    ChatCompletionMessageToolCall.builder()
                        .id(functionCall.callId())
                        .function(
                            ChatCompletionMessageToolCall.Function.builder()
                                .arguments(functionCall.arguments())
                                .name(functionCall.name())
                                .build()
                        )
                        .build()
                )
                .build()
        )
    }

    /**
     * Adds a function call output message to the completion request.
     *
     * @param item The function call output input item
     * @param completionBuilder The builder to add the message to
     */
    private fun addFunctionCallOutputMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        val output = item.asFunctionCallOutput()
        completionBuilder.addMessage(
            ChatCompletionToolMessageParam.builder()
                .content(output.output())
                .toolCallId(output.callId())
                .build()
        )
    }

    /**
     * Applies model and basic parameters to the completion request.
     *
     * @param completionBuilder The builder to add parameters to
     * @param params The source parameters
     */
    private fun applyModelAndParameters(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        completionBuilder.model(params.model())
        completionBuilder.temperature(params.temperature())
        completionBuilder.maxCompletionTokens(params.maxOutputTokens())
        // completionBuilder.metadata(params.metadata()) //TODO Unsupported in groq
        completionBuilder.topP(params.topP())
        //completionBuilder.store(params.store()) //TODO Unsupported in groq
    }

    /**
     * Applies tool configuration to the completion request.
     *
     * @param completionBuilder The builder to add tool configuration to
     * @param params The source parameters
     */
    private fun applyToolConfiguration(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        if (params.toolChoice().isPresent) {
            completionBuilder.toolChoice(createToolChoiceOption(params.toolChoice().get()))
        }

        if (params.tools().isPresent && params.tools().get().isNotEmpty()) {
            completionBuilder.tools(convertTools(params.tools().get()))
        }
    }

    /**
     * Creates a tool choice option based on the provided tool choice.
     *
     * @param toolChoice The tool choice from response parameters
     * @return ChatCompletionToolChoiceOption for the completion request
     */
    private fun createToolChoiceOption(toolChoice: ResponseCreateParams.ToolChoice): ChatCompletionToolChoiceOption {
        return when {
            toolChoice.isTypes() -> {
                ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .type(JsonValue.from(toolChoice.asTypes().type().asString().lowercase()))
                        .build()
                )
            }

            toolChoice.isFunction() -> {
                ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .function(JsonValue.from(toolChoice.asFunction().name().lowercase()))
                        .function(
                            ChatCompletionNamedToolChoice.Function.builder()
                                .name(toolChoice.asFunction().name()).build()
                        ).build()
                )
            }

            toolChoice.isOptions() -> {
                val toolChoiceOptions = toolChoice.asOptions()
                if (toolChoiceOptions.asString().lowercase() == "auto") {
                    ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO)
                } else {
                    ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.NONE)
                }
            }

            else -> throw IllegalArgumentException("Unsupported tool choice")
        }
    }

    /**
     * Converts response tools to chat completion tools.
     *
     * @param tools The tools from response parameters
     * @return List of ChatCompletionTool for the completion request
     */
    private fun convertTools(tools: List<Tool>): List<ChatCompletionTool> {

        return tools.map { responseTool ->
            when {
                responseTool.isFunction() -> {
                    val functionTool = responseTool.asFunction()
                    ChatCompletionTool.builder().function(
                        objectMapper.readValue(
                            objectMapper.writeValueAsString(functionTool),
                            FunctionDefinition::class.java
                        )
                    ).build()
                }

                responseTool.isWebSearch() -> {
                    val webSearchTool = responseTool.asWebSearch()
                    ChatCompletionTool.builder()
                        .type(JsonValue.from("function"))
                        .function(
                            FunctionDefinition.builder()
                                .name(webSearchTool.type().asString())
                                .build()
                        )
                        .build()
                }

                responseTool.isFileSearch() -> {
                    val fileSearchTool = responseTool.asFileSearch()
                    ChatCompletionTool.builder()
                        .type(JsonValue.from("function"))
                        .function(
                            FunctionDefinition.builder()
                                .name(fileSearchTool._type())
                                .build()
                        )
                        .build()
                }

                responseTool.isComputerUsePreview() -> {
                    val computerUsePreviewTool = responseTool.asComputerUsePreview()
                    ChatCompletionTool.builder()
                        .type(JsonValue.from("function"))
                        .function(
                            FunctionDefinition.builder()
                                .name(computerUsePreviewTool._type())
                                .build()
                        )
                        .build()
                }

                else -> throw IllegalArgumentException("Unsupported tool")
            }
        }
    }

    /**
     * Applies response formatting to the completion request.
     *
     * @param completionBuilder The builder to add formatting to
     * @param params The source parameters
     */
    private fun applyResponseFormatting(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        if (params.text().isPresent && params.text().get().format().isPresent) {
            val format = params.text().get().format().get()
            when {
                format.isText() -> {
                    completionBuilder.responseFormat(format.asText())
                }

                format.isJsonObject() -> {
                    completionBuilder.responseFormat(format.asJsonObject())
                }

                format.isJsonSchema() -> {
                    completionBuilder.responseFormat(
                        ResponseFormatJsonSchema.builder()
                            .type(format.asJsonSchema()._type())
                            .jsonSchema(
                                objectMapper.readValue(
                                    objectMapper.writeValueAsString(format.asJsonSchema().schema()),
                                    ResponseFormatJsonSchema.JsonSchema::class.java
                                )
                            )
                            .build()
                    )
                }
            }
        }
    }

    /**
     * Applies reasoning effort to the completion request if present.
     *
     * @param completionBuilder The builder to add reasoning effort to
     * @param params The source parameters
     */
    private fun applyReasoningEffort(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        if (params.reasoning().isPresent && params.reasoning().get().effort().isPresent) {
            completionBuilder.reasoningEffort(
                ReasoningEffort.of(params.reasoning().get().effort().get().asString())
            )
        }
    }

    /**
     * Converts input messages to chat completion messages based on their role.
     *
     * @param item The input item to convert
     * @param completionBuilder The builder to add the message to
     */
    private fun convertInputMessages(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        val role = when {
            item.isEasyInputMessage() -> item.asEasyInputMessage().role()
            item.isResponseOutputMessage() -> item.asResponseOutputMessage()._role()
            else -> item.asMessage().role()
        }

        when (role.toString().lowercase()) {
            "user" -> handleUserMessage(item, completionBuilder)
            "assistant" -> handleAssistantMessage(item, completionBuilder)
            "system" -> handleSystemMessage(item, completionBuilder)
            "developer" -> handleDeveloperMessage(item, completionBuilder)
            "tool" -> handleToolMessage(item, completionBuilder)
        }
    }

    /**
     * Handles conversion of user messages.
     *
     * @param item The user message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleUserMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            when {
                easyInputMessage.content().isTextInput() -> {
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam.builder().content(
                            ChatCompletionUserMessageParam.Content.ofText(
                                easyInputMessage.content().asTextInput()
                            )
                        ).build()
                    )
                }

                easyInputMessage.content().isResponseInputMessageContentList() -> {
                    val contentList = easyInputMessage.content().asResponseInputMessageContentList()
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam.builder().content(
                            ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                prepareUserContent(contentList)
                            )
                        ).build()
                    )
                }

                else -> {
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam.builder().content(
                            ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                prepareUserContent(
                                    item.asMessage()
                                )
                            )
                        ).build()
                    )
                }
            }
        }
    }

    /**
     * Handles conversion of assistant messages.
     *
     * @param item The assistant message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleAssistantMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            completionBuilder.addMessage(
                ChatCompletionAssistantMessageParam.builder().content(
                    ChatCompletionAssistantMessageParam.Content.ofText(
                        item.asEasyInputMessage().content().asTextInput()
                    )
                ).build()
            )
        } else if (item.isResponseOutputMessage()) {
            item.asResponseOutputMessage().content().forEach {
                val outputText = it.asOutputText().text()
                completionBuilder.addMessage(
                    ChatCompletionAssistantMessageParam.builder().content(
                        ChatCompletionAssistantMessageParam.Content.ofText(outputText)
                    ).build()
                )
            }
        }
    }

    /**
     * Handles conversion of system messages.
     *
     * @param item The system message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleSystemMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            when {
                easyInputMessage.content().isTextInput() -> {
                    completionBuilder.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(
                                easyInputMessage.content().asTextInput()
                            ).build()
                    )
                }

                easyInputMessage.content().isResponseInputMessageContentList() -> {
                    completionBuilder.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(
                                easyInputMessage.content().asResponseInputMessageContentList()
                                    .first().asInputText().text()
                            ).build()
                    )
                }
            }
        }
    }

    /**
     * Handles conversion of developer messages.
     *
     * @param item The developer message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleDeveloperMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            when {
                easyInputMessage.content().isTextInput() -> {
                    completionBuilder.addMessage(
                        ChatCompletionDeveloperMessageParam.builder()
                            .content(
                                easyInputMessage.content().asTextInput()
                            ).build()
                    )
                }

                easyInputMessage.content().isResponseInputMessageContentList() -> {
                    completionBuilder.addMessage(
                        ChatCompletionDeveloperMessageParam.builder()
                            .content(
                                easyInputMessage.content().asResponseInputMessageContentList()
                                    .first().asInputText().text()
                            ).build()
                    )
                }
            }
        }
    }

    /**
     * Handles conversion of tool messages.
     *
     * @param item The tool message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleToolMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            completionBuilder.addMessage(
                ChatCompletionToolMessageParam.builder()
                    .content(easyInputMessage.content().asTextInput())
                    .toolCallId(
                        easyInputMessage._additionalProperties()["tool_call_id"].toString()
                    )
                    .build()
            )
        }
    }
}

/**
 * Prepares user content from a message.
 *
 * @param message The message to extract content from
 * @return List of ChatCompletionContentPart objects
 */
private fun prepareUserContent(message: ResponseInputItem.Message): List<ChatCompletionContentPart> =
    prepareUserContent(message.content())

/**
 * Prepares user content from a list of response input content.
 * Converts various input types (text, image, file) to appropriate ChatCompletionContentPart objects.
 *
 * @param contentList List of response input content
 * @return List of ChatCompletionContentPart objects
 */
private fun prepareUserContent(contentList: List<ResponseInputContent>): List<ChatCompletionContentPart> =
    contentList.map { content ->
        when {
            content.isInputText() -> {
                val inputText = content.asInputText()
                ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(
                        inputText.text()
                    ).build()
                )
            }

            content.isInputImage() -> {
                val inputImage = content.asInputImage()
                ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder().type(JsonValue.from("image_url")).imageUrl(
                        ChatCompletionContentPartImage.ImageUrl.builder()
                            .url(inputImage._imageUrl())
                            .detail(
                                ChatCompletionContentPartImage.ImageUrl.Detail.of(
                                    inputImage.detail().value().name.lowercase()
                                )
                            )
                            .putAllAdditionalProperties(inputImage._additionalProperties())
                            .build()
                    ).build()
                )
            }

            content.isInputFile() -> {
                val inputFile = content.asInputFile()
                ChatCompletionContentPart.ofFile(
                    ChatCompletionContentPart.File.builder().type(JsonValue.from("file")).file(
                        ChatCompletionContentPart.File.FileObject.builder()
                            .fileData(inputFile._fileData())
                            .fileId(inputFile._fileId())
                            .fileName(inputFile._filename()).build()
                    ).build()
                )
            }

            else -> throw IllegalArgumentException("Unsupported input type")
        }
    }