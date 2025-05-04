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
    private val streamingService: MasaicStreamingService,
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
                // TODO : Uncomment and implement telemetryService.withChatCompletionTimer
                // var completion = telemetryService.withChatCompletionTimer(params, metadata) { client.chat().completions().create(params) }
                var completion = client.chat().completions().create(params)
                
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

        // If the main block returned directly (no tool calls initially),
        // the chatCompletion object here holds the final result.
        // The storeCompletion call inside the block handles storing when tool calls are resolved.
        // However, we need to ensure it's stored if there were NO tool calls at all.
        // The logic above inside the block handles this now.
        // No, wait. If there are no tool calls, the block returns completion, then storeCompletion outside is called.
        // If there ARE tool calls, handleToolCalls is called. handleToolCalls will call storeCompletion only if it returns early due to non-native tools.
        // If handleToolCalls proceeds recursively, the FINAL recursive call's observation block will eventually store the very last completion.
        // This seems correct. The external call below might be redundant.

        // Let's remove the potentially redundant external call and rely on the logic within the observation block and handleToolCalls.
        // storeCompletion(chatCompletion, params)
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
        // *** IMPORTANT: Need to adjust exceedsMaxToolCalls logic ***
        // It should probably count ASSISTANT messages with tool_calls, not TOOL messages.
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
            
            // Set stream parameter to true explicitly
            val streamingParams = params.toBuilder().additionalBodyProperties(mapOf("stream" to JsonValue.from(true))).build()
            
            // Create the streaming response
            val response =
                client
                    .chat()
                    .completions()
                    .createStreaming(streamingParams)
            
            // Convert to server-sent events
            streamChatCompletionToServerSentEvents(response, observation, metadata)
                .catch { error ->
                    logger.error(error) { "Error in streaming chat completion" }
                    val errorEvent =
                        ServerSentEvent
                            .builder<String>()
                            .event("error")
                            .data("{\"message\":\"Error in streaming completion: ${error.message}\"}")
                            .build()
                    emit(errorEvent)
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
     */
    private suspend fun storeCompletion(
        completion: ChatCompletion,
        params: ChatCompletionCreateParams,
    ) {
        // For now, assume we always store if this method is called.
        // Add conditional logic here based on where the 'store' flag comes from.
        // Example: if (metadata.store == true || params.shouldStore() /* hypothetical */ ) { ... }
        logger.info { "Storing completion ID: ${completion.id()}" }
        val messages = params.messages()
        val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
        val context = CompletionToolRequestContext(aliasMap, params)

        // Use the injected completionStore
        completionStore.storeCompletion(completion, messages, context)
        // logger.debug moved into the store implementations
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
        // Or perhaps count the number of TOOL messages if that's the intended limit?
        // val toolExecutionTurns = messages.count { it.role() == ChatCompletionMessage.Role.TOOL }

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
} 
