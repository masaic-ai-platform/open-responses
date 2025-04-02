package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.*
import com.openai.models.responses.*
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.ReactorContext
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import kotlin.coroutines.coroutineContext

/**
 * Implementation of ResponseService for Masaic OpenAI API client.
 * This service handles communication with OpenAI's API for chat completions
 * and provides methods to create, retrieve, and stream responses.
 *
 * This implementation follows the Single Responsibility Principle by delegating
 * specific tasks to specialized helper classes.
 *
 */
@Service
class MasaicOpenAiResponseServiceImpl(
    private val parameterConverter: MasaicParameterConverter,
    private val toolHandler: MasaicToolHandler,
    private val streamingService: MasaicStreamingService,
    private val telemetryService: TelemetryService,
) : ResponseService {
    private val logger = KotlinLogging.logger {}

    /**
     * Not implemented: Returns a version of this service that includes raw HTTP response data.
     */
    override fun withRawResponse(): ResponseService.WithRawResponse {
        logger.warn { "withRawResponse() method not implemented" }
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Returns the input items service for this response service.
     */
    override fun inputItems(): InputItemService {
        logger.warn { "inputItems() method not implemented" }
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun create(
        params: ResponseCreateParams,
        requestOptions: RequestOptions,
    ): Response {
        logger.warn { "create() method with RequestOptions not implemented" }
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Creates a new completion response based on provided parameters.
     * Enhanced with OpenTelemetry GenAI span semantics.
     *
     * @param client OpenAI client to use for the request
     * @param params Parameters for creating the response
     * @return Response object containing completion data
     */
    suspend fun create(
        client: OpenAIClient,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput = CreateResponseMetadataInput(),
    ): Response {
        val parentObservation =
            coroutineContext[ReactorContext]?.context?.get<Observation>(
                ObservationThreadLocalAccessor.KEY,
            )

        val responseOrCompletions =
            telemetryService.withClientObservation("open.responses.create", parentObservation) { observation ->
                logger.debug { "Creating completion with model: ${params.model()}" }
                val completionCreateParams = parameterConverter.prepareCompletion(params)
                telemetryService.emitModelInputEvents(observation, completionCreateParams, metadata)
                val chatCompletions = telemetryService.withTimer(params, metadata) { client.chat().completions().create(completionCreateParams) }
                logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }
                telemetryService.emitModelOutputEvents(observation, chatCompletions, metadata)
                telemetryService.setAllObservationAttributes(observation, chatCompletions, params, metadata)
                telemetryService.recordTokenUsage(metadata, chatCompletions, params, "input", chatCompletions.usage().get().promptTokens())
                telemetryService.recordTokenUsage(metadata, chatCompletions, params, "output", chatCompletions.usage().get().completionTokens())

                if (!hasToolCalls(chatCompletions)) {
                    logger.info { "No tool calls detected, returning direct response" }
                    return@withClientObservation chatCompletions.toResponse(params)
                }

                chatCompletions
            }

        if (responseOrCompletions is Response) {
            return responseOrCompletions
        }
        val chatCompletions = responseOrCompletions as ChatCompletion
        val responseInputItems = toolHandler.handleMasaicToolCall(chatCompletions, params, parentObservation)
        val updatedParams =
            params
                .toBuilder()
                .input(ResponseCreateParams.Input.ofResponse(responseInputItems))
                .build()

        if (hasUnresolvedFunctionCalls(updatedParams)) {
            logger.info { "Some function calls without outputs, returning current response" }
            return chatCompletions.toResponse(updatedParams)
        }

        if (exceedsMaxToolCalls(responseInputItems)) {
            val errorMsg = "Too many tool calls. Increase limit by setting MASAIC_MAX_TOOL_CALLS."
            logger.error { errorMsg }
            throw IllegalArgumentException(errorMsg)
        }

        return create(client, updatedParams, metadata)
    }

    /**
     * Returns true if the [chatCompletions] contain any tool_calls finish reason.
     */
    private fun hasToolCalls(chatCompletion: ChatCompletion): Boolean =
        chatCompletion.choices().any {
            it
                .finishReason()
                .value()
                .name
                .lowercase() == "tool_calls"
        }

    /**
     * Returns true if the new parameters have more function calls than function outputs,
     * indicating unresolved calls that need to be returned.
     */
    private fun hasUnresolvedFunctionCalls(params: ResponseCreateParams): Boolean {
        val numFunctionCalls = params.input().asResponse().count { it.isFunctionCall() }
        val numFunctionOutputs = params.input().asResponse().count { it.isFunctionCallOutput() }
        return numFunctionCalls > numFunctionOutputs
    }

    /**
     * Returns true if the [items] exceed the max allowed function calls set in the environment.
     */
    private fun exceedsMaxToolCalls(items: List<ResponseInputItem>): Boolean = items.count { it.isFunctionCall() } > getAllowedMaxToolCalls()

    /**
     * Creates a streaming completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     *
     * @param initialParams Parameters for creating the completion
     * @return Flow of ServerSentEvents containing response chunks
     */
    suspend fun createCompletionStream(
        client: OpenAIClient,
        initialParams: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
    ): Flow<ServerSentEvent<String>> {
        logger.debug { "Creating streaming completion with model: ${initialParams.model()}" }
        return streamingService.createCompletionStream(client, initialParams, metadata)
    }

    /**
     * Not implemented: Creates a streaming response.
     */
    override fun createStreaming(
        params: ResponseCreateParams,
        requestOptions: RequestOptions,
    ): StreamResponse<ResponseStreamEvent> {
        logger.warn { "createStreaming() method with RequestOptions not implemented" }
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Retrieves a specific response by ID.
     */
    override fun retrieve(
        params: ResponseRetrieveParams,
        requestOptions: RequestOptions,
    ): Response {
        logger.warn { "retrieve() method not implemented" }
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Deletes a response by ID.
     */
    override fun delete(
        params: ResponseDeleteParams,
        requestOptions: RequestOptions,
    ) {
        logger.warn { "delete() method not implemented" }
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Gets the maximum allowed tool calls from environment or default.
     */
    private fun getAllowedMaxToolCalls(): Int {
        val maxToolCalls = System.getenv("MASAIC_MAX_TOOL_CALLS")?.toInt() ?: 10
        logger.trace { "Maximum allowed tool calls: $maxToolCalls" }
        return maxToolCalls
    }
}
