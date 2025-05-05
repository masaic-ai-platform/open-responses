package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.EventUtils
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.*
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
            val errorMsg = "Maximum tool call limit (${getAllowedMaxToolCalls()}) reached. Increase limit by setting MASAIC_MAX_TOOL_CALLS."
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
        logger.debug { "Creating streaming chat completion with model: ${params.model()}" }
        
        val parentObservation =
            coroutineContext[ReactorContext]?.context?.get<Observation>(
                ObservationThreadLocalAccessor.KEY,
            )
        
        return telemetryService.withClientObservation("openai.chat.completions.stream", parentObservation) { observation ->
            logger.debug { "Streaming chat completion with model: ${params.model()}" }
            
            telemetryService.emitModelInputEvents(observation, params, metadata)
            
            // Process the streaming request with tool call handling
            flow {
                // Process the initial streaming segment
                val streamResult = processStreamSegment(client, params, observation, metadata)
                
                // Emit all the chunks from the first segment
                emitAll(streamResult.chunks)
                
                // If a tool call was detected and we have a reconstructed completion
                if (streamResult.completion != null && hasToolCalls(streamResult.completion)) {
                    logger.info { "Tool calls detected in stream completion ${streamResult.completion.id()}, handling tool calls" }
                    
                    // Call the wrapper function to handle tool calls and prepare for possible next step
                    val handlingResult =
                        handleToolCallsAndPrepareNextStep(
                            streamResult.completion,
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
                        }
                        handlingResult.updatedParams != null -> {
                            // We have new parameters after handling native tools, make a recursive call
                            logger.info { "Native tools handled successfully, continuing with updated conversation for completion ${streamResult.completion.id()}" }
                            // Call createCompletionStream recursively and emit all its events
                            emitAll(createCompletionStream(client, handlingResult.updatedParams, metadata))
                        }
                        else -> {
                            // This case shouldn't happen if logic is correct, but just in case
                            logger.warn { "Tool handling complete but no next steps determined for completion ${streamResult.completion.id()}" }
                        }
                    }
                }
            }.catch { error ->
                logger.error(error) { "Error in streaming chat completion with tool handling" }
                emit(
                    ServerSentEvent
                        .builder<String>()
                        .event("error")
                        .data("{\"message\":\"Error in streaming completion: ${error.message}\"}")
                        .build(),
                )
            }.onCompletion { error ->
                if (error != null) {
                    logger.error(error) { "Stream completed with error" }
                } else {
                    logger.info { "Stream completed successfully" }
                }
            }
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
                
                // Check for tool_calls finish reason
                if (choice.finishReason().isPresent && 
                    choice
                        .finishReason()
                        .get()
                        .value()
                        .name ==
                    ChatCompletion.Choice.FinishReason.TOOL_CALLS
                        .value()
                        .name
                ) {
                    hasToolCallsFinishReason = true
                }
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
            if (hasToolCallsFinishReason && finalCompletionId != null) {
                reconstructChatCompletion(
                    finalCompletionId, // Use the captured non-null value
                    contentBuffers,
                    toolCallBuffers,
                    params,
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
        params: ChatCompletionCreateParams,
    ): ChatCompletion? {
        try {
            // Create choices from buffers
            val choices = mutableListOf<ChatCompletion.Choice>()
            
            // Process each choice index
            val allIndices = (contentBuffers.keys + toolCallBuffers.keys).toSet()
            
            for (index in allIndices) {
                // Get content
                val content = contentBuffers[index]?.toString() ?: ""
                
                // Get tool calls for this choice
                val deltaTookCalls = toolCallBuffers[index]
                
                // Skip choices without valid tool calls if we're completing a tool call sequence
                if (deltaTookCalls.isNullOrEmpty()) {
                    // Only add text content if we have some
                    if (content.isNotBlank()) {
                        val textMessage =
                            ChatCompletionMessage
                                .builder()
                                .role(JsonValue.from("assistant"))
                                .content(content)
                                .build()
                            
                        choices.add(
                            ChatCompletion.Choice
                                .builder()
                                .index(index.toLong())
                                .message(textMessage)
                                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                                .build(),
                        )
                    }
                    continue
                }
                
                // Consolidate tool calls by ID to handle partial updates
                val consolidatedToolCalls = mutableMapOf<String, MutableMap<String, String>>()
                
                // First pass: collect all tool call information
                for (toolCall in deltaTookCalls) {
                    if (!toolCall.function().isPresent) continue
                    
                    val id = toolCall.id().getOrNull() ?: continue
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
                val completedToolCalls = mutableListOf<ChatCompletionMessageToolCall>()
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
                
                // Only proceed if we have valid tool calls
                if (completedToolCalls.isEmpty()) continue
                
                // Create the message with tool calls
                val message =
                    ChatCompletionMessage
                        .builder()
                        .role(JsonValue.from("assistant"))
                        .refusal(null)
                        .content("")
                
                // Add all completed tool calls
                completedToolCalls.forEach { message.addToolCall(it) }
                
                // Create the choice
                choices.add(
                    ChatCompletion.Choice
                        .builder()
                        .index(index.toLong())
                        .logprobs(null)
                        .message(message.build())
                        .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                        .build(),
                )
            }
            
            // Only proceed if we have valid choices
            if (choices.isEmpty()) return null
            
            // Build the complete ChatCompletion
            return ChatCompletion
                .builder()
                .id(completionId)
                .created(System.currentTimeMillis() / 1000) // Current time in seconds
                .model(params.model().toString())
                .choices(choices)
                .build()
        } catch (e: Exception) {
            logger.error(e) { "Failed to reconstruct ChatCompletion from stream chunks" }
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
            val errorMsg = "Maximum tool call limit (${getAllowedMaxToolCalls()}) reached. Increase limit by setting MASAIC_MAX_TOOL_CALLS."
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
            }

        val maxAllowed = getAllowedMaxToolCalls()
        // Using toolCallTurns seems more aligned with preventing infinite loops
        val exceeds = toolCallTurns >= maxAllowed
        if (exceeds) {
            logger.warn { "Detected $toolCallTurns assistant tool call requests, which meets or exceeds the limit of $maxAllowed." }
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
