package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.api.utils.EventUtils
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.stream.consumeAsFlow
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.UUID
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
        metadata: InstrumentationMetadataInput = InstrumentationMetadataInput(),
    ): ChatCompletion {
        logger.debug { "Creating chat completion with model: ${params.model()}" }

        var initialChatCompletion =
            telemetryService.withClientObservation("chat", metadata.modelName) { observation ->
                var completion = telemetryService.withChatCompletionTimer(params, metadata) { client.chat().completions().create(params) }
                telemetryService.emitModelInputEvents(observation, params, metadata)

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
                completion
            }

        // Check for tool calls that need to be handled
        if (hasToolCalls(initialChatCompletion)) {
            logger.info { "Tool calls detected in completion ${initialChatCompletion.id()}, initiating tool handling flow." }
            // Call handleToolCalls which will recursively call create if needed, or return a terminal completion
            return runBlocking { handleToolCalls(initialChatCompletion, params, client, metadata) }
        }
        
        // No tool calls, store and return the original completion
        if (params.store().getOrDefault(false)) {
            runBlocking { storeCompletion(initialChatCompletion, params) }
        }

        logger.debug { "Final chat completion ID: ${initialChatCompletion.id()}" }
        return initialChatCompletion
    }

    /**
     * Handles tool calls in the chat completion. Executes native tools or terminates for specific tools like image_generation.
     * If non-native tools are encountered, returns the original completion to the client.
     * Otherwise, recursively calls create with tool outputs included.
     */
    private suspend fun handleToolCalls(
        chatCompletion: ChatCompletion, // The completion from LLM that might contain tool calls
        originalParams: ChatCompletionCreateParams, // Original params from the client for this cycle
        client: OpenAIClient,
        metadata: InstrumentationMetadataInput,
    ): ChatCompletion {
        logger.info { "Handling tool calls for completion ID: ${chatCompletion.id()}" }

        when (val toolCallOutcome = toolHandler.handleCompletionToolCall(chatCompletion, originalParams, client)) {
            is CompletionToolCallOutcome.Terminate -> {
                logger.info { "Terminal tool executed (e.g. image_generation). Completion ID: ${toolCallOutcome.finalChatCompletion.id()}" }
                if (originalParams.store().getOrDefault(false)) {
                    // Store with the full message history leading to termination
                    storeCompletion(toolCallOutcome.finalChatCompletion, originalParams, toolCallOutcome.messagesForStorage)
                }
                return toolCallOutcome.finalChatCompletion
            }
            is CompletionToolCallOutcome.Continue -> {
                if (toolCallOutcome.hasUnresolvedClientTools) {
                    logger.info { "Non-native tool calls requiring client handling detected for completion ID: ${chatCompletion.id()}. Returning original completion." }
                    // Store the completion *before* returning, as it contains the tool calls the client needs
                    if (originalParams.store().getOrDefault(false)) {
                        // Store original completion with its original messages
                        storeCompletion(chatCompletion, originalParams, originalParams.messages())
                    }
                    return chatCompletion // Return the original completion for client handling
                }

                // All tools were native and handled (and not terminal).
                logger.debug { "All tool calls were native and handled. Proceeding with recursive call for completion ID: ${chatCompletion.id()}" }

                val updatedMessages = toolCallOutcome.updatedMessages
                if (exceedsMaxToolCalls(updatedMessages)) {
                    val errorMsg = "Maximum tool call limit (${getAllowedMaxToolCalls()}) reached. Increase limit by setting OPEN_RESPONSES_MAX_TOOL_CALLS."
                    logger.error { errorMsg }
                    // TODO: Instead of throwing, maybe return an error ChatCompletion?
                    throw IllegalStateException(errorMsg)
                }

                val nextParams = originalParams.toBuilder().messages(updatedMessages).build()
                logger.debug { "Prepared updated parameters with ${updatedMessages.size} messages for recursive call." }
                return create(client, nextParams, metadata) // Recursive call
            }
        }
    }

    /**
     * Helper to store completion with potentially overridden messages for context.
     */
    private suspend fun storeCompletion(
        completion: ChatCompletion,
        originalParams: ChatCompletionCreateParams,
        messagesForStorage: List<ChatCompletionMessageParam>? = null,
    ) {
        val paramsForStorage =
            if (messagesForStorage != null) {
                originalParams.toBuilder().messages(messagesForStorage).build()
            } else {
                originalParams
            }
        // Create context with alias mappings
        val aliasMap = toolService.buildAliasMap(paramsForStorage.tools().orElse(emptyList()))
        val context = CompletionToolRequestContext(aliasMap, paramsForStorage)
        completionStore.storeCompletion(completion, paramsForStorage.messages(), context)
        logger.debug { "Stored completion with ID: ${completion.id()} with ${paramsForStorage.messages().size} messages in context." }
    }

    /**
     * Creates a streaming chat completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     * Supports native tool execution during streaming, including terminal tools like image_generation.
     */
    suspend fun createCompletionStream(
        client: OpenAIClient,
        params: ChatCompletionCreateParams,
        metadata: InstrumentationMetadataInput,
    ): Flow<ServerSentEvent<String>> {
        logger.debug { "Creating streaming chat completion with model: ${params.model()}" }

        val observation = telemetryService.startObservation("chat", metadata.modelName)
        var finalCompletionForTelemetry: ChatCompletion? = null // To hold the very final completion for telemetry

        try {
            telemetryService.emitModelInputEvents(observation, params, metadata)
            
            return flow {
                var currentParams = params
                var continueLoop = true

                while (continueLoop) {
                    val streamResponse = client.chat().completions().createStreaming(currentParams)
                    val currentIterationCompletionChunks = mutableListOf<ChatCompletionChunk>()
                    var reconstructedCompletionFromStream: ChatCompletion? = null

                    // Collect all chunks from the current stream segment
                    streamResponse.stream().consumeAsFlow().collect { chunk ->
                        currentIterationCompletionChunks.add(chunk)
                        val jsonChunk = objectMapper.writeValueAsString(chunk)
                        emit(
                            ServerSentEvent
                                .builder<String>()
                                .id(chunk.id())
                                .event("chunk") 
                                .data(jsonChunk)
                                .build(),
                        )
                    }
                    
                    // Try to reconstruct the completion from this segment's chunks for tool call detection
                    // This reconstruction is a simplified view; a more robust one might be needed.
                    reconstructedCompletionFromStream =
                        ChatCompletionConverter.reconstructFromChunks(
                            currentIterationCompletionChunks, 
                            currentParams.model().toString(), // Pass model from current params
                        )
                    
                    if (reconstructedCompletionFromStream == null) {
                        logger.warn { "Could not reconstruct completion from stream chunks. Cannot check for tool calls." }
                        finalCompletionForTelemetry =
                            ChatCompletion
                                .builder() // Create a minimal one if needed for telemetry
                                .id("unknown-stream-id-${UUID.randomUUID()}")
                                .model(currentParams.model().toString())
                                .created(System.currentTimeMillis() / 1000L)
                                .choices(emptyList())
                                .build()
                        continueLoop = false // Stop if reconstruction fails
                        break
                    }
                    
                    finalCompletionForTelemetry = reconstructedCompletionFromStream // Update for telemetry for this segment

                    if (hasToolCalls(reconstructedCompletionFromStream)) {
                        logger.info { "Tool calls detected in stream completion ${reconstructedCompletionFromStream.id()}, handling tool calls." }
                        
                        val toolHandlingResult =
                            handleToolCallsAndPrepareNextStep(
                                reconstructedCompletionFromStream,
                                currentParams, // Pass current params of this iteration
                                client,
                                metadata,
                            )

                        when {
                            toolHandlingResult.isTerminal && toolHandlingResult.terminalChatCompletion != null -> {
                                logger.info { "Terminal tool executed in stream. Finalizing stream with ChatCompletion ID: ${toolHandlingResult.terminalChatCompletion.id()}" }
                                finalCompletionForTelemetry = toolHandlingResult.terminalChatCompletion // This is the true final completion
                                
                                // Store this terminal completion
                                if (currentParams.store().getOrDefault(false) && toolHandlingResult.messagesForStorage != null) {
                                    storeCompletion(toolHandlingResult.terminalChatCompletion, currentParams, toolHandlingResult.messagesForStorage)
                                } else if (currentParams.store().getOrDefault(false)) {
                                    storeCompletion(toolHandlingResult.terminalChatCompletion, currentParams) // Fallback if messagesForStorage is null
                                }

                                // It's already an assistant message with content and STOP.
                                val terminalChunk =
                                    ChatCompletionChunk
                                        .builder()
                                        .id(toolHandlingResult.terminalChatCompletion.id())
                                        .choices(
                                            toolHandlingResult.terminalChatCompletion.choices().map { choice ->
                                                ChatCompletionChunk.Choice
                                                    .builder()
                                                    .index(choice.index())
                                                    .delta(
                                                        ChatCompletionChunk.Choice.Delta
                                                            .builder()
                                                            .content(choice.message().content())
                                                            .build(),
                                                    ).finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                                                    .build()
                                            },
                                        ).model(toolHandlingResult.terminalChatCompletion.model())
                                        .created(toolHandlingResult.terminalChatCompletion.created())
                                        .usage(toolHandlingResult.terminalChatCompletion.usage().getOrNull())
                                        .build()
                                // emit(EventUtils.convertChunkEvent(terminalChunk, objectMapper))
                                val jsonTerminalChunk = objectMapper.writeValueAsString(terminalChunk)
                                emit(
                                    ServerSentEvent
                                        .builder<String>()
                                        .id(terminalChunk.id())
                                        .event("chunk") 
                                        .data(jsonTerminalChunk)
                                        .build(),
                                )
                                continueLoop = false // Stop the main loop
                            }
                            toolHandlingResult.hasUnresolvedClientTools -> {
                                logger.info { "Non-native tool calls detected for completion ${reconstructedCompletionFromStream.id()}, client to handle." }
                                // Chunks already emitted. Loop will stop.
                                continueLoop = false
                            }
                            toolHandlingResult.updatedParams != null -> {
                                logger.info { "Native tools handled, continuing stream with updated params for ${reconstructedCompletionFromStream.id()}." }
                                currentParams = toolHandlingResult.updatedParams // Prepare for next iteration
                                // continueLoop remains true
                            }
                            else -> { // No updated params, no client tools, not terminal -> effectively complete normally.
                                logger.info { "Stream segment processed, no further tool actions or recursion needed for ${reconstructedCompletionFromStream.id()}." }
                                continueLoop = false
                            }
                        }
                    } else {
                        // No tool calls in this segment, current loop ends.
                        logger.info { "No tool calls in current stream segment for ${reconstructedCompletionFromStream.id()}." }
                        continueLoop = false
                    }

                    if (!continueLoop) {
                        emit(EventUtils.doneEvent()) // Emit [DONE] if loop is to be exited
                    }
                } // End of while(continueLoop)
            }.catch { error ->
                logger.error(error) { "Error in streaming chat completion flow" }
                emit(
                    ServerSentEvent
                        .builder<String>()
                        .event("error")
                        .data("{\"message\":\"Error in streaming completion: ${error.message}\"}")
                        .build(),
                )
                // Ensure [DONE] is sent even on error if flow is broken by exception
                emit(EventUtils.doneEvent())
            }.onCompletion { error ->
                finalCompletionForTelemetry?.let { completionToLog ->
                    if (error == null) {
                        logger.info { "Stream completed. Processing final telemetry for completion ID: ${completionToLog.id()}." }
                    } else {
                        logger.warn(error) { "Stream completed with error. Processing final telemetry for completion ID: ${completionToLog.id()}." }
                    }
                    telemetryService.emitModelOutputEvents(observation, completionToLog, metadata)
                    telemetryService.setChatCompletionObservationAttributes(observation, completionToLog, params, metadata)
                    if (completionToLog.usage().isPresent) {
                        telemetryService.recordChatCompletionTokenUsage(metadata, completionToLog, params, "input", completionToLog.usage().get().promptTokens())
                        telemetryService.recordChatCompletionTokenUsage(metadata, completionToLog, params, "output", completionToLog.usage().get().completionTokens())
                    }
                } ?: run {
                    logger.warn { "Final completion for telemetry was null." }
                }
                observation.stop()
                logger.debug { "Stopped observation ${observation.context.name} for streaming chat completion." }
            }
        } catch (t: Throwable) {
            logger.error(t) { "Outer error in createCompletionStream setup." }
            observation.error(t)
            observation.stop()
            throw t
        }
    }

    /**
     * Handles tool calls detected in a streaming completion, prepares for the next step.
     */
    private suspend fun handleToolCallsAndPrepareNextStep(
        chatCompletion: ChatCompletion, // The reconstructed completion from the current stream segment
        currentIterationParams: ChatCompletionCreateParams,
        client: OpenAIClient,
        metadata: InstrumentationMetadataInput,
    ): ToolHandlingStreamResult {
        logger.info { "Handling tool calls for streamed completion ID: ${chatCompletion.id()}" }

        return when (val toolCallOutcome = toolHandler.handleCompletionToolCall(chatCompletion, currentIterationParams, client)) {
            is CompletionToolCallOutcome.Terminate -> {
                logger.info { "Terminal tool executed in stream. ChatCompletion ID: ${toolCallOutcome.finalChatCompletion.id()}" }
                ToolHandlingStreamResult(
                    isTerminal = true, 
                    terminalChatCompletion = toolCallOutcome.finalChatCompletion,
                    messagesForStorage = toolCallOutcome.messagesForStorage, // Pass messages for storage
                )
            }
            is CompletionToolCallOutcome.Continue -> {
                if (toolCallOutcome.hasUnresolvedClientTools) {
                    logger.info { "Non-native tools detected in stream for completion ID: ${chatCompletion.id()}. Client to handle." }
                    ToolHandlingStreamResult(hasUnresolvedClientTools = true)
                } else {
                    // Native tools were handled, not terminal.
                    val updatedMessages = toolCallOutcome.updatedMessages
                    if (exceedsMaxToolCalls(updatedMessages)) {
                        val errorMsg = "Max tool calls reached in stream for ${chatCompletion.id()}."
                        logger.error { errorMsg }
                        // How to propagate this error in stream? For now, log and treat as unresolved.
                        // Ideally, an error event should be sent.
                        return ToolHandlingStreamResult(hasUnresolvedClientTools = true) // Treat as unresolved for now
                    }
                    val nextParams = currentIterationParams.toBuilder().messages(updatedMessages).build()
                    logger.debug { "Native tools handled in stream. Next params have ${nextParams.messages().size} messages for ${chatCompletion.id()}." }
                    ToolHandlingStreamResult(updatedParams = nextParams)
                }
            }
        }
    }

    /**
     * Checks if the given ChatCompletion has any tool calls.
     */
    private fun hasToolCalls(chatCompletion: ChatCompletion): Boolean =
        chatCompletion.choices().any {
            it.message().toolCalls().isPresent &&
                it
                    .message()
                    .toolCalls()
                    .get()
                    .isNotEmpty()
        }

    /**
     * Checks if the number of tool calls in messages exceeds the allowed limit.
     * Counts assistant messages with tool_calls and subsequent tool messages.
     */
    private fun exceedsMaxToolCalls(messages: List<ChatCompletionMessageParam>): Boolean {
        val toolCallSequences =
            messages.count {
                it.isAssistant() &&
                    it.asAssistant().toolCalls().isPresent &&
                    it
                        .asAssistant()
                        .toolCalls()
                        .get()
                        .isNotEmpty()
            }
        return toolCallSequences > getAllowedMaxToolCalls()
    }

    /**
     * Gets the maximum allowed tool calls from environment or default.
     */
    private fun getAllowedMaxToolCalls(): Int {
        val maxToolCalls = System.getenv("OPEN_RESPONSES_MAX_TOOL_CALLS")?.toInt() ?: 10 // Default to 10
        logger.trace { "Maximum allowed tool calls: $maxToolCalls" }
        return maxToolCalls
    }
}

/**
 * Data class to hold the results of tool handling in streaming mode.
 */
private data class ToolHandlingStreamResult(
    val updatedParams: ChatCompletionCreateParams? = null, // Params for next LLM call if any
    val hasUnresolvedClientTools: Boolean = false, // True if client needs to handle tools
    val isTerminal: Boolean = false, // True if a terminal tool (e.g., image_generation) was executed
    val terminalChatCompletion: ChatCompletion? = null, // The direct ChatCompletion from a terminal tool
    val messagesForStorage: List<ChatCompletionMessageParam>? = null, // Full message history for storage on termination
)
