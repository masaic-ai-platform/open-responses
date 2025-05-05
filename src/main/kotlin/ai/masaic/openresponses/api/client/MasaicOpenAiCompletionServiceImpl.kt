package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import ai.masaic.openresponses.api.support.service.GenAIObsAttributes
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.EventUtils
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.*
import com.openai.models.completions.CompletionUsage
import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull

/**
 * Implementation of OpenAI chat completions service for Masaic API client.
 * This service handles communication with OpenAI's API for chat completions
 * and provides methods to create, retrieve, and stream completions.
 */
@Service
class MasaicOpenAiCompletionServiceImpl(
    private val toolHandler: MasaicToolHandler,
    private val completionStore: CompletionStore,
    private val telemetryService: TelemetryService,
    private val toolService: ToolService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Creates a new chat completion based on provided parameters.
     * Enhanced with OpenTelemetry GenAI span semantics.
     *
     * @param client OpenAI client to use for the request
     * @param params Parameters for creating the chat completion
     * @param metadata Metadata for the completion
     * @return ChatCompletion object containing completion data
     */
    suspend fun create(
        client: OpenAIClient,
        params: ChatCompletionCreateParams,
        metadata: CreateResponseMetadataInput = CreateResponseMetadataInput(),
    ): ChatCompletion {
        val parentObservation =
            coroutineContext[ReactorContext]?.context?.get<Observation>(
                ObservationThreadLocalAccessor.KEY,
            )

        val chatCompletion =
            telemetryService.withClientObservation("openai.chat.completions.create", parentObservation) { observation ->
                logger.debug { "Creating chat completion with model: ${params.model()}" }
                
                telemetryService.emitModelInputEvents(observation, params, metadata)

                var completion = telemetryService.withChatCompletionTimer(params, metadata) { client.chat().completions().create(params) }

                // Generate ID if missing
                if (completion._id().isMissing()) {
                    completion = completion.toBuilder().id(UUID.randomUUID().toString()).build()
                }
                
                logger.debug { "Received chat completion with ID: ${completion.id()}" }
                telemetryService.emitModelOutputEvents(observation, completion, metadata)
                telemetryService.setChatCompletionObservationAttributes(observation, completion, params, metadata)
                
                if (completion.usage().isPresent) {
                    telemetryService.recordChatCompletionTokenUsage(metadata, completion, params, "input", completion.usage().get().promptTokens())
                    telemetryService.recordChatCompletionTokenUsage(metadata, completion, params, "output", completion.usage().get().completionTokens())
                }

                // Check for tool calls that need to be handled
                if (hasToolCalls(completion)) {
                    logger.info { "Tool calls detected in completion ${completion.id()}, initiating tool handling flow." }
                    // Call handleToolCalls which will recursively call create if needed
                    val response = runBlocking { handleToolCalls(completion, params, parentObservation, client, metadata) }

                    // Store final completion if no tool calls needed further handling
                    if (params.store().getOrDefault(false)) {
                        runBlocking { storeCompletion(response, params) }
                    }
                    return@withClientObservation response // Return the final completion
                }
                // Store final completion if no tool calls needed further handling
                if (params.store().getOrDefault(false)) {
                    runBlocking { storeCompletion(completion, params) }
                }
                completion
            }
        logger.debug { "Final chat completion ID: ${chatCompletion.id()}" }
        return chatCompletion
    }

    /**
     * Handles tool calls in the chat completion. Executes native tools.
     * If non-native tools are encountered, returns the original completion to the client.
     * Otherwise, recursively calls create with tool outputs included.
     */
    private suspend fun handleToolCalls(
        chatCompletion: ChatCompletion,
        params: ChatCompletionCreateParams,
        parentObservation: Observation?,
        client: OpenAIClient,
        metadata: CreateResponseMetadataInput,
    ): ChatCompletion {
        logger.info { "Handling tool calls for completion ID: ${chatCompletion.id()}" }

        // Call the handler and get the result object
        val toolHandlingResult =
            toolHandler.handleCompletionToolCall(
                chatCompletion = chatCompletion,
                params = params,
                parentObservation = parentObservation,
                openAIClient = client,
            )

        // Check the flag from the result
        if (toolHandlingResult.hasUnresolvedClientTools) {
            logger.info { "Non-native tool calls requiring client handling detected for completion ID: ${chatCompletion.id()}. Returning original completion." }
            // Store the completion *before* returning, as it contains the tool calls the client needs
            // Note: params here are the *original* params, not the updated ones
            if (params.store().getOrDefault(false)) {
                storeCompletion(chatCompletion, params)
            }
            return chatCompletion // Return the original completion for client handling
        }

        // If we reach here, all tools were native and handled.
        logger.debug { "All tool calls were native. Proceeding with recursive call for completion ID: ${chatCompletion.id()}" }

        // Use the messages returned by the handler (contains native outputs)
        val updatedMessages = toolHandlingResult.updatedMessages

        // Check if max tool calls reached *after* processing this round
        if (exceedsMaxToolCalls(updatedMessages)) {
            val errorMsg = "Maximum tool call limit (${getAllowedMaxToolCalls()}) reached. Increase limit by setting OPEN_RESPONSES_MAX_TOOL_CALLS."
            logger.error { errorMsg }
            throw IllegalStateException(errorMsg)
        }

        // Create new parameters with updated messages (including native tool outputs)
        val updatedParams = params.toBuilder().messages(updatedMessages).build()
        logger.debug { "Prepared updated parameters with ${updatedMessages.size} messages for recursive call." }

        // Call create recursively with the updated params and original metadata
        return create(client, updatedParams, metadata)
    }

    /**
     * Creates a streaming chat completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     * Supports native tool execution during streaming.
     *
     * @param client OpenAI client to use for the request
     * @param params Parameters for creating the completion
     * @param metadata Metadata for the completion
     * @return Flow of ServerSentEvents containing completion chunks
     */
    suspend fun createCompletionStream(
        client: OpenAIClient,
        params: ChatCompletionCreateParams,
        metadata: CreateResponseMetadataInput,
    ): Flow<ServerSentEvent<String>> {
        val parentObservation =
            coroutineContext[ReactorContext]?.context?.get<Observation>(
                ObservationThreadLocalAccessor.KEY,
            )

        // Start observation manually
        val observation = telemetryService.startObservation("openai.chat.completions.stream", parentObservation)
        logger.debug { "Started observation ${observation.context.name} for streaming chat completion with model: ${params.model()}" }
        
        // Variable to hold the final reconstructed completion
        var finalCompletion: ChatCompletion? = null
        
        try {
            telemetryService.emitModelInputEvents(observation, params, metadata)
            
            // Process the streaming request with tool call handling
            return flow {
                // Process the initial streaming segment
                val streamResult = processStreamSegment(client, params, observation, metadata)
                
                // Store the completion from this segment if available
                streamResult.completion?.let { finalCompletion = it }
                
                // Emit all the chunks from the first segment
                emitAll(streamResult.chunks)
                
                // If a tool call was detected and we have a reconstructed completion
                if (streamResult.completion != null && hasToolCalls(streamResult.completion)) {
                    logger.info { "Tool calls detected in stream completion ${streamResult.completion.id()}, handling tool calls" }
                    
                    // Call the wrapper function to handle tool calls and prepare for possible next step
                    val handlingResult =
                        handleToolCallsAndPrepareNextStep(
                            streamResult.completion, // Use completion from this segment
                            params,
                            parentObservation,
                            client,
                            metadata,
                        )
                    
                    // Based on the result, we either stop, continue recursively, or return the original chunks
                    when {
                        handlingResult.hasUnresolvedClientTools -> {
                            // Client needs to handle these tool calls, we're done (already emitted all chunks)
                            logger.info { "Non-native tool calls detected for completion ${streamResult.completion.id()}, returning to client for handling" }
                            // No further actions needed - chunks were already emitted above
                            // The finalCompletion still holds the result with tool calls for client
                        }
                        handlingResult.updatedParams != null -> {
                            // We have new parameters after handling native tools, make a recursive call
                            logger.info { "Native tools handled successfully, continuing with updated conversation for completion ${streamResult.completion.id()}" }
                            // Call createCompletionStream recursively and emit all its events
                            // The recursive call's flow will eventually update finalCompletion in its onCompletion
                            emitAll(createCompletionStream(client, handlingResult.updatedParams, metadata))
                        }
                        else -> {
                            // This case shouldn't happen if logic is correct, but just in case
                            logger.warn { "Tool handling complete but no next steps determined for completion ${streamResult.completion.id()}" }
                        }
                    }
                }
                // If no tool calls, the flow just finishes here after emitting chunks.
                // The 'finalCompletion' variable holds the result from processStreamSegment.
            }.catch { error ->
                logger.error(error) { "[OBS:${observation.context.name}] Error in streaming chat completion flow" }
                observation.error(error) // Record error in observation
                emit(
                    ServerSentEvent
                        .builder<String>()
                        .event("error")
                        .data("{\"message\":\"Error in streaming completion: ${error.message}\"}")
                        .build(),
                )
            }.onCompletion { error ->
                if (error == null) {
                    logger.info { "Stream completed successfully. Processing final telemetry." }
                    // Process final telemetry only if the stream completed without errors
                    finalCompletion?.let { completion ->
                        logger.debug { "Emitting final telemetry for completion ID: ${completion.id()}" }
                        // Emit output events
                        telemetryService.emitModelOutputEvents(observation, completion, metadata)

                        // Set final observation attributes
                        telemetryService.setChatCompletionObservationAttributes(observation, completion, params, metadata)

                        // Record token usage if available
                        if (completion.usage().isPresent) {
                            val usage = completion.usage().get()
                            // Note: We might not have accurate prompt tokens from the stream reconstructed object
                            // Using the value from usage if present, otherwise might need alternative tracking
                            telemetryService.recordChatCompletionTokenUsage(metadata, completion, params, "input", usage.promptTokens())
                            telemetryService.recordChatCompletionTokenUsage(metadata, completion, params, "output", usage.completionTokens())
                        } else {
                            logger.warn { "Usage data not found in final reconstructed completion for ID: ${completion.id()}" }
                        }

                        // Store final completion AFTER telemetry
                        if (params.store().getOrDefault(false)) {
                            // Ensure storage happens within a suspend context if needed by the store implementation
                            // Use runBlocking if storeCompletion is not suspend and needs blocking context
                            runBlocking { storeCompletion(completion, params) }
                        }
                    } ?: run {
                        logger.warn { "Final reconstructed completion was null, cannot record final telemetry." }
                        // Optionally set some basic attributes if completion is null but stream succeeded
                        observation.lowCardinalityKeyValue("gen_ai.response.finish_reason", "unknown")
                    }
                } else {
                    logger.error(error) { "[OBS:${observation.context.name}] Stream completed with error. Final telemetry potentially incomplete." }
                    // Error already recorded in catch block
                    observation.lowCardinalityKeyValue("gen_ai.response.finish_reason", "error")
                }
                // Stop the observation here, after all final attributes/events are set
                observation.stop()
                logger.debug { "[OBS:${observation.context.name}] Observation stopped in onCompletion." }
            }
        } catch (e: Exception) {
            // Handle exceptions during flow creation/initialization
            logger.error(e) { "[OBS:${observation.context.name}] Error during stream setup before flow execution." }
            observation.error(e)
            observation.lowCardinalityKeyValue(GenAIObsAttributes.ERROR_TYPE, e.javaClass.simpleName)
            observation.lowCardinalityKeyValue("gen_ai.response.finish_reason", "error")
            observation.stop() // Ensure observation is stopped on creation error
            throw e
        }
    }

    /**
     * Process a single streaming segment (one call to OpenAI's streaming API).
     * Collects all chunks and attempts to reconstruct a complete ChatCompletion if the
     * stream ends with tool_calls finish reason.
     *
     * @param client OpenAI client to use
     * @param params Chat completion parameters 
     * @param observation Current telemetry observation
     * @param metadata Metadata for the completion
     * @return StreamSegmentResult containing the flow of chunks and possibly a reconstructed completion
     */
    private suspend fun processStreamSegment(
        client: OpenAIClient,
        params: ChatCompletionCreateParams,
        observation: Observation,
        metadata: CreateResponseMetadataInput,
    ): StreamSegmentResult {
        logger.debug { "Processing stream segment with model: ${params.model()}" }
        
        // Set stream parameter to true explicitly
        val streamingParams = params.toBuilder().additionalBodyProperties(mapOf("stream" to JsonValue.from(true))).build()
        
        // Create the streaming response
        val response = client.chat().completions().createStreaming(streamingParams)
        
        // Convert to server-sent events 
        val events = mutableListOf<ServerSentEvent<String>>()
        
        // Buffers to reconstruct the completion
        var hasToolCallsFinishReason = false
        var completionId: String? = null
        val contentBuffers = mutableMapOf<Int, StringBuilder>()
        val toolCallBuffers = mutableMapOf<Int, MutableList<ChatCompletionChunk.Choice.Delta.ToolCall>>()
        val finishReasons = mutableMapOf<Int, ChatCompletionChunk.Choice.FinishReason>() // Store finish reason per choice index
        var usage: CompletionUsage? = null // Store usage data
        
        // Collect all chunks and detect tool call finish reason
        response.stream().forEach { chunk ->
            completionId = chunk.id()
            
            // Convert chunk to ServerSentEvent and collect it
            val jsonChunk = objectMapper.writeValueAsString(chunk)
            val event =
                ServerSentEvent
                    .builder<String>()
                    .id(chunk.id())
                    .event("chunk")
                    .data(jsonChunk)
                    .build()
            events.add(event)
            
            // Buffer content and tool calls for possible reconstruction
            chunk.choices().forEach { choice ->
                val index = choice.index().toInt()
                
                // Buffer content
                if (choice.delta().content().isPresent) {
                    val content = choice.delta().content().get() ?: ""
                    contentBuffers.getOrPut(index) { StringBuilder() }.append(content)
                }
                
                // Buffer tool calls
                if (choice.delta().toolCalls().isPresent) {
                    val toolCalls = choice.delta().toolCalls().get()
                    if (toolCalls.isNotEmpty()) {
                        toolCallBuffers.getOrPut(index) { mutableListOf() }.addAll(toolCalls)
                    }
                }
                
                // Check for finish reason and store it
                if (choice.finishReason().isPresent) {
                    val reason = choice.finishReason().get()
                    finishReasons[index] = reason // Store the specific finish reason
                    if (reason.value().name ==
                        ChatCompletion.Choice.FinishReason.TOOL_CALLS
                            .value()
                            .name
                    ) {
                        hasToolCallsFinishReason = true
                    }
                }
            }
            
            // Capture usage data if present (usually in the last chunk)
            if (chunk.usage().isPresent) {
                usage = chunk.usage().get()
            }
        }
        
        // Add DONE event
        events.add(
            ServerSentEvent
                .builder<String>()
                .event("done")
                .data("[DONE]")
                .build(),
        )
        
        // Fix for smart cast: Capture the potentially null completionId
        val finalCompletionId = completionId
        
        // If we detected tool calls, reconstruct a ChatCompletion
        val reconstructedCompletion =
            if (finalCompletionId != null) { // Reconstruct always if we have an ID
                reconstructChatCompletion(
                    finalCompletionId,
                    contentBuffers,
                    toolCallBuffers,
                    finishReasons, // Pass finish reasons
                    params,
                    usage, // Pass usage data
                )
            } else {
                null
            }
        
        // Create flow of events
        val eventsFlow =
            flow {
                events.forEach { emit(it) }
            }
        
        return StreamSegmentResult(eventsFlow, reconstructedCompletion)
    }

    /**
     * Reconstructs a ChatCompletion object from buffered streaming chunks.
     * 
     * @param completionId ID of the completion
     * @param contentBuffers Map of choice index to content StringBuilder
     * @param toolCallBuffers Map of choice index to list of tool calls
     * @param params Original parameters used for the completion
     * @return Reconstructed ChatCompletion or null if reconstruction fails
     */
    private fun reconstructChatCompletion(
        completionId: String,
        contentBuffers: Map<Int, StringBuilder>,
        toolCallBuffers: Map<Int, MutableList<ChatCompletionChunk.Choice.Delta.ToolCall>>,
        finishReasons: Map<Int, ChatCompletionChunk.Choice.FinishReason>, // Added finish reasons map
        params: ChatCompletionCreateParams,
        usage: CompletionUsage?, // Added usage data
    ): ChatCompletion? {
        try {
            // Create choices from buffers
            val choices = mutableListOf<ChatCompletion.Choice>()
            
            // Process each choice index
            val allIndices = (contentBuffers.keys + toolCallBuffers.keys + finishReasons.keys).toSet()
            
            for (index in allIndices) {
                // Get content
                val content = contentBuffers[index]?.toString()
                
                // Get tool calls for this choice
                val deltaTookCalls = toolCallBuffers[index]
                
                // Get finish reason, default to STOP if not present for this index but content/tools are
                val finishReason = finishReasons[index] ?: ChatCompletion.Choice.FinishReason.STOP
                
                // Determine if this choice involves tool calls
                val hasToolCallsForIndex = !deltaTookCalls.isNullOrEmpty()
                
                // Skip choice if it has no content, no tool calls, and no explicit finish reason
                if (content.isNullOrEmpty() && !hasToolCallsForIndex && !finishReasons.containsKey(index)) {
                    continue
                }
                
                // Consolidate tool calls by ID
                val completedToolCalls = mutableListOf<ChatCompletionMessageToolCall>()
                if (hasToolCallsForIndex) {
                    val consolidatedToolCalls = mutableMapOf<String, MutableMap<String, String>>()
                    // First pass: collect all tool call information
                    deltaTookCalls?.forEach { toolCall ->
                        if (!toolCall.function().isPresent) return@forEach
                        
                        val id = toolCall.id().getOrNull() ?: return@forEach
                        val function = toolCall.function().get()
                        
                        val toolInfo = consolidatedToolCalls.getOrPut(id) { mutableMapOf() }
                        
                        // Update name if present
                        if (function.name().isPresent) {
                            toolInfo["name"] = function.name().get()
                        }
                        
                        // Update arguments if present
                        if (function.arguments().isPresent) {
                            val newArgs = function.arguments().get()
                            val currentArgs = toolInfo["arguments"] ?: ""
                            toolInfo["arguments"] = currentArgs + newArgs
                        }
                    }
                    
                    // Build the complete tool calls
                    for ((id, toolInfo) in consolidatedToolCalls) {
                        // Skip tool calls with missing information
                        val name = toolInfo["name"] ?: continue
                        val arguments = toolInfo["arguments"] ?: ""
                        
                        completedToolCalls.add(
                            ChatCompletionMessageToolCall
                                .builder()
                                .id(id)
                                .function(
                                    ChatCompletionMessageToolCall.Function
                                        .builder()
                                        .name(name)
                                        .arguments(arguments)
                                        .build(),
                                ).build(),
                        )
                    }
                }
                
                // Build the message
                val messageBuilder =
                    ChatCompletionMessage
                        .builder()
                        .role(JsonValue.from("assistant"))
                        .refusal(null)
                
                // Add content if present
                content?.let { messageBuilder.content(it) }
                
                // Add tool calls if present
                if (completedToolCalls.isNotEmpty()) {
                    completedToolCalls.forEach { messageBuilder.addToolCall(it) }
                    // If content is null/empty but we have tool calls, set content to empty string if needed by builder
                    if (content.isNullOrEmpty()) {
                        messageBuilder.content("")
                    }
                } else if (content.isNullOrEmpty()) {
                    // If no content AND no tool calls, this choice might be invalid unless there's a finish reason only
                    // If finishReason is STOP, allow empty content. If TOOL_CALLS, it's inconsistent.
                    if (finishReason == ChatCompletion.Choice.FinishReason.STOP) {
                        messageBuilder.content("") // Ensure content is non-null for the builder
                    } else {
                        logger.warn { "Skipping choice index $index: No content or tool calls, but finish reason is $finishReason" }
                        continue // Skip this potentially inconsistent choice
                    }
                }
                
                // Create the choice
                choices.add(
                    ChatCompletion.Choice
                        .builder()
                        .index(index.toLong())
                        .logprobs(null)
                        .message(messageBuilder.build())
                        .finishReason(ChatCompletion.Choice.FinishReason.of(finishReason.toString())) // Use the stored finish reason
                        .build(),
                )
            }
            
            // Only proceed if we have valid choices
            if (choices.isEmpty()) {
                logger.warn { "Reconstruction resulted in zero valid choices for completion ID: $completionId" }
                return null
            }
            
            // Build the complete ChatCompletion
            val completionBuilder =
                ChatCompletion
                    .builder()
                    .id(completionId)
                    .created(System.currentTimeMillis() / 1000) // Current time in seconds
                    .model(params.model().toString()) // Use request model as fallback
                    .choices(choices)
            
            // Add usage if available
            usage?.let { completionBuilder.usage(it) }
            
            return completionBuilder.build()
        } catch (e: Exception) {
            logger.error(e) { "Failed to reconstruct ChatCompletion from stream chunks for ID: $completionId" }
            return null
        }
    }

    /**
     * Handles tool calls from a streaming completion and prepares for the next step.
     * Similar to handleToolCalls but returns a structured result for streaming flow control.
     *
     * @param chatCompletion Reconstructed chat completion with tool calls
     * @param params Original chat completion parameters
     * @param parentObservation Parent observation for telemetry
     * @param client OpenAI client
     * @param metadata Metadata for the completion
     * @return ToolHandlingStreamResult with information about next steps
     */
    private suspend fun handleToolCallsAndPrepareNextStep(
        chatCompletion: ChatCompletion,
        params: ChatCompletionCreateParams,
        parentObservation: Observation?,
        client: OpenAIClient,
        metadata: CreateResponseMetadataInput,
    ): ToolHandlingStreamResult {
        val completionId = chatCompletion.id()
        logger.info { "Handling tool calls for streaming completion ID: $completionId" }
        
        // Count and log the number of tool calls being processed
        val totalToolCalls =
            chatCompletion.choices().sumOf { choice ->
                choice
                    .message()
                    .toolCalls()
                    .getOrDefault(emptyList())
                    .size
            }
        
        logger.info { "Processing $totalToolCalls tool calls for streaming completion ID: $completionId" }
        
        // Log individual tool calls for debugging
        chatCompletion.choices().forEach { choice ->
            choice.message().toolCalls().getOrDefault(emptyList()).forEach { toolCall ->
                val function = toolCall.function()
                val toolName = function.name()
                val toolId = toolCall.id()
                val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
                val context = CompletionToolRequestContext(aliasMap, params)
                val isNative = toolService.getFunctionTool(toolName, context) != null
                
                logger.info { "Tool call in completion $completionId: name='$toolName', id='$toolId', isNative=$isNative" }
            }
        }
        
        // Call the handler and get the result object
        val toolHandlingResult =
            toolHandler.handleCompletionToolCall(
                chatCompletion = chatCompletion,
                params = params,
                parentObservation = parentObservation,
                openAIClient = client,
            )
        
        // Check if there are unresolved client tools
        if (toolHandlingResult.hasUnresolvedClientTools) {
            logger.info { "Non-native tool calls requiring client handling detected for completion ID: $completionId" }
            
            // Store the completion if requested (it contains the tool calls the client needs)
            if (params.store().getOrDefault(false)) {
                storeCompletion(chatCompletion, params)
            }
            
            // Return result indicating client needs to handle tool calls
            return ToolHandlingStreamResult(
                hasUnresolvedClientTools = true,
                updatedParams = null,
            )
        }
        
        // All tools were native and handled
        logger.info { "All tool calls were native in streaming completion ID: $completionId" }
        
        // Get updated messages with tool results
        val updatedMessages = toolHandlingResult.updatedMessages
        logger.debug { "Updated message count after tool handling: ${updatedMessages.size}" }
        
        // Check if max tool calls reached
        if (exceedsMaxToolCalls(updatedMessages)) {
            val errorMsg = "Maximum tool call limit (${getAllowedMaxToolCalls()}) reached. Increase limit by setting OPEN_RESPONSES_MAX_TOOL_CALLS."
            logger.error { errorMsg }
            throw IllegalStateException(errorMsg)
        }
        
        // Create new parameters with updated messages
        val updatedParams = params.toBuilder().messages(updatedMessages).build()
        logger.debug { "Prepared updated parameters with ${updatedMessages.size} messages for next stream segment" }
        
        // Return result for continuing with updated parameters
        return ToolHandlingStreamResult(
            hasUnresolvedClientTools = false,
            updatedParams = updatedParams,
        )
    }

    /**
     * Converts a chat completion streaming response to server-sent events.
     */
    private fun streamChatCompletionToServerSentEvents(
        response: StreamResponse<ChatCompletionChunk>,
        observation: Observation,
        metadata: CreateResponseMetadataInput,
    ): Flow<ServerSentEvent<String>> =
        EventUtils.convertChatCompletionStreamToServerSentEvents(
            response,
            objectMapper,
            observation,
            metadata,
            telemetryService,
        )

    /**
     * Stores a chat completion and its associated messages in the completion store.
     * Only stores if the store flag is set to true in the parameters.
     *
     * @param completion The ChatCompletion to store
     * @param params The original request parameters
     */
    private suspend fun storeCompletion(
        completion: ChatCompletion,
        params: ChatCompletionCreateParams,
    ) {
        val completionId = completion.id()
        val shouldStore = params.store().getOrDefault(false)
        
        if (!shouldStore) {
            logger.debug { "Skipping storage for completion ID: $completionId (store flag is false)" }
            return
        }
        
        logger.info { "Storing completion ID: $completionId in completion store" }
        
        try {
            val messages = params.messages()
            val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
            val context = CompletionToolRequestContext(aliasMap, params)

            // Use the injected completionStore
            completionStore.storeCompletion(completion, messages, context)
            logger.debug { "Successfully stored completion ID: $completionId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to store completion ID: $completionId" }
            // Don't rethrow - storage failures shouldn't break the user experience
        }
    }

    /**
     * Returns true if the chat completion contains any tool_calls finish reason.
     */
    private fun hasToolCalls(chatCompletion: ChatCompletion): Boolean =
        chatCompletion.choices().any { choice ->
            // Check if finishReason is present and is TOOL_CALLS
            choice.finishReason().value().name ==
                ChatCompletion.Choice.FinishReason.TOOL_CALLS
                    .value()
                    .name
        }

    /**
     * Returns true if the messages exceed the max allowed function calls set in the environment.
     */
    private fun exceedsMaxToolCalls(messages: List<ChatCompletionMessageParam>): Boolean {
        // Count assistant messages that contain tool calls.
        val toolCallTurns =
            messages.count {
                it.isAssistant() &&
                    it
                        .asAssistant()
                        .toolCalls()
                        .getOrNull()
                        ?.isNotEmpty() == true
            } + // Also count tool messages as part of a turn
                messages.count { it.isTool() } / 2 // Approximately, as each tool call usually gets one tool response

        val maxAllowed = getAllowedMaxToolCalls()
        // Using toolCallTurns seems more aligned with preventing infinite loops
        val exceeds = toolCallTurns >= maxAllowed
        if (exceeds) {
            logger.warn { "Detected $toolCallTurns tool call turns, which meets or exceeds the limit of $maxAllowed." }
        }
        return exceeds
    }

    /**
     * Gets the maximum allowed tool calls from environment or default.
     */
    private fun getAllowedMaxToolCalls(): Int {
        val maxToolCalls = System.getenv("OPEN_RESPONSES_MAX_TOOL_CALLS")?.toInt() ?: 25
        logger.trace { "Maximum allowed tool calls: $maxToolCalls" }
        return maxToolCalls
    }

    /**
     * Result class for processStreamSegment
     */
    private data class StreamSegmentResult(
        val chunks: Flow<ServerSentEvent<String>>,
        val completion: ChatCompletion?,
    )

    /**
     * Result class for handleToolCallsAndPrepareNextStep
     */
    private data class ToolHandlingStreamResult(
        val hasUnresolvedClientTools: Boolean,
        val updatedParams: ChatCompletionCreateParams?,
    )
} 
