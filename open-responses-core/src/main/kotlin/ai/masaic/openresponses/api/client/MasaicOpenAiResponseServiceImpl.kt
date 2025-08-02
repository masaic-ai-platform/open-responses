package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.tool.ToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.models.chat.completions.*
import com.openai.models.responses.*
import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.ReactorContext
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.jvm.optionals.getOrNull

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
    private val responseStore: ResponseStore,
    private val telemetryService: TelemetryService,
    private val toolService: ToolService,
) {
    private val logger = KotlinLogging.logger {}

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
        metadata: InstrumentationMetadataInput = InstrumentationMetadataInput(),
    ): Response {
        // Extract any existing HTTP server span from Reactor context
        val parentObs: Observation? = coroutineContext[ReactorContext]?.context?.get(ObservationThreadLocalAccessor.KEY)
        var chatObservation: Observation? = null
        val responseOrCompletions =
            telemetryService.withClientObservation("chat", metadata.modelName, parentObs) { observation ->
                chatObservation = observation
                logger.debug { "Creating completion with model: ${params.model()}" }
                val completionCreateParams = parameterConverter.prepareCompletion(params)
                telemetryService.emitModelInputEvents(observation, completionCreateParams, metadata)
                var chatCompletions = telemetryService.withTimer(params, metadata) { client.chat().completions().create(completionCreateParams) }
                if (chatCompletions._id().isMissing()) {
                    chatCompletions = chatCompletions.toBuilder().id(UUID.randomUUID().toString()).build()
                }
                logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }
                telemetryService.emitModelOutputEvents(observation, chatCompletions, metadata)
                telemetryService.setAllObservationAttributes(observation, chatCompletions, params, metadata)
                telemetryService.recordTokenUsage(metadata, chatCompletions, params, "input", chatCompletions.usage().get().promptTokens())
                telemetryService.recordTokenUsage(metadata, chatCompletions, params, "output", chatCompletions.usage().get().completionTokens())

                if (!hasToolCalls(chatCompletions)) {
                    logger.info { "No tool calls detected, returning direct response" }
                    // Convert ChatCompletion to Response before returning
                    val directResponse = chatCompletions.toResponse(params)
                    // Store this direct response as well
                    storeResponseWithInputItems(directResponse, params)
                    return@withClientObservation directResponse
                }

                chatCompletions
            }

        if (responseOrCompletions is Response) {
            return responseOrCompletions
        }
        val chatCompletions = responseOrCompletions as ChatCompletion

        // Handle tool calls and decide next step, passing the chat span for proper trace linkage
        when (val toolCallOutcome = toolHandler.handleMasaicToolCall(chatCompletions, params, chatObservation, client)) {
            is MasaicToolCallResult.Terminate -> {
                logger.info { "Terminal tool executed (e.g., image_generation). Returning direct response." }
                val tempParamsForStorage =
                    params
                        .toBuilder()
                        .input(ResponseCreateParams.Input.ofResponse(toolCallOutcome.finalResponseInputItems))
                        .build()

                storeResponseWithInputItems(toolCallOutcome.directResponse, tempParamsForStorage)
                return toolCallOutcome.directResponse
            }
            is MasaicToolCallResult.Continue -> {
                val responseInputItems = toolCallOutcome.items
                val updatedParams =
                    params
                        .toBuilder()
                        .input(ResponseCreateParams.Input.ofResponse(responseInputItems))
                        .build()

                if (hasUnresolvedFunctionCalls(updatedParams)) {
                    logger.info { "Some function calls without outputs, returning current response based on assistant's request for tools" }
                    val currentResponseWithToolRequests = chatCompletions.toResponse(updatedParams) // pass updatedParams
                    storeResponseWithInputItems(currentResponseWithToolRequests, updatedParams)
                    return currentResponseWithToolRequests
                }

                if (exceedsMaxToolCalls(responseInputItems)) {
                    val errorMsg = "Too many tool calls. Increase limit by setting OPEN_RESPONSES_MAX_TOOL_CALLS."
                    logger.error { errorMsg }
                    throw IllegalArgumentException(errorMsg)
                }
                // Recursive call to continue processing with the outputs of the executed tools
                return create(client, updatedParams, metadata)
            }
        }
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
     * Stores a response and its associated input items in the response store.
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
        metadata: InstrumentationMetadataInput,
    ): Flow<ServerSentEvent<String>> {
        logger.debug { "Creating streaming completion with model: ${initialParams.model()}" }
        return streamingService.createCompletionStream(client, initialParams, metadata)
    }

    /**
     * Retrieves a specific response by ID.
     *
     * @param params The parameters containing the response ID to retrieve
     * @param requestOptions Additional request options
     * @return The retrieved response or throws an exception if not found
     */
    suspend fun retrieveAsync(
        params: ResponseRetrieveParams,
        requestOptions: RequestOptions,
    ): Response {
        val responseId = params.responseId().getOrNull() ?: throw IllegalArgumentException("Response ID is required")
        logger.debug { "Retrieving response with ID: $responseId" }

        // Attempt to retrieve the response from the store
        val response = responseStore.getResponse(responseId)
        if (response != null) {
            logger.debug { "Found response with ID: $responseId" }
            return response
        }

        // If response is not found, throw an exception
        logger.error { "Response with ID: $responseId not found" }
        throw NoSuchElementException("Response with ID: $responseId not found")
    }

    /**
     * Deletes a response by ID.
     */
    suspend fun deleteAsync(
        params: ResponseDeleteParams,
        requestOptions: RequestOptions,
    ) {
        val responseId = params.responseId().getOrNull() ?: throw IllegalArgumentException("Response ID is required")
        logger.debug { "Deleting response with ID: $responseId" }

        val response = responseStore.getResponse(responseId)

        if (response == null) {
            logger.error { "Response with ID: $responseId not found" }
            throw NoSuchElementException("Response with ID: $responseId not found")
        }

        val deleted = responseStore.deleteResponse(responseId)
        if (deleted) {
            logger.info { "Successfully deleted response with ID: $responseId" }
        }
    }

    /**
     * Gets the maximum allowed tool calls from environment or default.
     */
    private fun getAllowedMaxToolCalls(): Int {
        val maxToolCalls = System.getenv("OPEN_RESPONSES_MAX_TOOL_CALLS")?.toInt() ?: System.getenv("MASAIC_MAX_TOOL_CALLS")?.toInt() ?: 25
        logger.trace { "Maximum allowed tool calls: $maxToolCalls" }
        return maxToolCalls
    }
}

/**
 * Prepares user content from a message.
 *
 * @param message The message to extract content from
 * @return List of ChatCompletionContentPart objects
 */
private fun prepareUserContent(message: ResponseInputItem.Message): List<ChatCompletionContentPart> = prepareUserContent(message.content())

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
                    ChatCompletionContentPartText
                        .builder()
                        .text(
                            inputText.text(),
                        ).build(),
                )
            }

            content.isInputImage() -> {
                val inputImage = content.asInputImage()
                ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage
                        .builder()
                        .type(JsonValue.from("image_url"))
                        .imageUrl(
                            ChatCompletionContentPartImage.ImageUrl
                                .builder()
                                .url(inputImage._imageUrl())
                                .detail(
                                    ChatCompletionContentPartImage.ImageUrl.Detail.of(
                                        inputImage
                                            .detail()
                                            .value()
                                            .name
                                            .lowercase(),
                                    ),
                                ).putAllAdditionalProperties(inputImage._additionalProperties())
                                .build(),
                        ).build(),
                )
            }

            content.isInputFile() -> {
                val inputFile = content.asInputFile()
                ChatCompletionContentPart.ofFile(
                    ChatCompletionContentPart.File
                        .builder()
                        .type(JsonValue.from("file"))
                        .file(
                            ChatCompletionContentPart.File.FileObject
                                .builder()
                                .fileData(inputFile._fileData())
                                .fileId(inputFile._fileId())
                                .filename(inputFile._filename())
                                .build(),
                        ).build(),
                )
            }

            else -> throw IllegalArgumentException("Unsupported input type")
        }
    }
