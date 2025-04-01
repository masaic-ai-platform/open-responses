package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.core.jsonMapper
import com.openai.models.chat.completions.*
import com.openai.models.responses.*
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service

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
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) : ResponseService {
    private val logger = KotlinLogging.logger {}

    // Constants for GenAI span attributes
    private object GenAiAttributes {
        val OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name")
        val SYSTEM = AttributeKey.stringKey("gen_ai.system")
        val REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model")
        val REQUEST_TEMPERATURE = AttributeKey.doubleKey("gen_ai.request.temperature")
        val REQUEST_MAX_TOKENS = AttributeKey.longKey("gen_ai.request.max_tokens")
        val REQUEST_TOP_P = AttributeKey.doubleKey("gen_ai.request.top_p")
        val RESPONSE_ID = AttributeKey.stringKey("gen_ai.response.id")
        val RESPONSE_MODEL = AttributeKey.stringKey("gen_ai.response.model")
        val RESPONSE_FINISH_REASONS = AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")
        val USAGE_INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens")
        val USAGE_OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens")
        val OUTPUT_TYPE = AttributeKey.stringKey("gen_ai.output.type")
        val ERROR_TYPE = AttributeKey.stringKey("error.type")
    }

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
    fun create(
        client: OpenAIClient,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput = CreateResponseMetadataInput(),
    ): Response {
        // 1. Start a CLIENT span, call the block, end the span in a finally
        val responseOrCompletions =
            withClientObservation { observation ->
                logger.debug { "Creating completion with model: ${params.model()}" }
                val completionCreateParams = parameterConverter.prepareCompletion(params)
                emitModelInputEvents(observation, completionCreateParams, metadata)
                val chatCompletions =
                    withTimer("open.responses.create", metadata, params) {
                        client.chat().completions().create(completionCreateParams)
                    }

                logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }
                emitModelOutputEvents(observation, chatCompletions, metadata)
                // Set any relevant span attributes
                setAllObservationAttributes(observation, chatCompletions, params, metadata)

                // If no tool calls => return direct response
                if (!hasToolCalls(chatCompletions)) {
                    logger.info { "No tool calls detected, returning direct response" }
                    return@withClientObservation chatCompletions.toResponse(params)
                }

                recordTokenUsage(metadata, chatCompletions, params, "input", chatCompletions.usage().get().promptTokens())
                recordTokenUsage(metadata, chatCompletions, params, "output", chatCompletions.usage().get().completionTokens())
                // Otherwise, return the raw ChatCompletion so we can handle tools afterward
                chatCompletions
            }

        // 2. If we got a direct Response, just return it
        if (responseOrCompletions is Response) {
            return responseOrCompletions
        }

        // 3. Otherwise, we have a ChatCompletion => handle tool calls and possibly recurse
        val chatCompletions = responseOrCompletions as ChatCompletion
        val responseInputItems = toolHandler.handleMasaicToolCall(chatCompletions, params)
        val updatedParams =
            params
                .toBuilder()
                .input(ResponseCreateParams.Input.ofResponse(responseInputItems))
                .build()

        // If we have function calls but no outputs => return partial response
        if (hasUnresolvedFunctionCalls(updatedParams)) {
            logger.info { "Some function calls without outputs, returning current response" }
            return chatCompletions.toResponse(updatedParams)
        }

        // Ensure we haven’t exceeded the maximum number of calls
        if (exceedsMaxToolCalls(responseInputItems)) {
            val errorMsg = "Too many tool calls. Increase limit by setting MASAIC_MAX_TOOL_CALLS."
            logger.error { errorMsg }
            throw IllegalArgumentException(errorMsg)
        }

        // 4. Recurse with updated params, opening a new span
        return create(client, updatedParams, metadata)
    }

    private fun emitModelInputEvents(
        observation: Observation,
        inputParams: ChatCompletionCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        val mapper = jsonMapper()
        inputParams.messages().forEach { message ->
            val (role, eventName, content) =
                when {
                    message.isUser() ->
                        Triple("user", "gen_ai.user.message", message.user().get().content())
                    message.isAssistant() &&
                        message
                            .assistant()
                            .get()
                            .content()
                            .isPresent ->
                        Triple("assistant", "gen_ai.assistant.system", message.assistant().get().content())
                    message.isAssistant() &&
                        message
                            .assistant()
                            .get()
                            .toolCalls()
                            .isPresent -> {
                        val toolCalls =
                            message
                                .assistant()
                                .get()
                                .toolCalls()
                                .get()
                        val tools =
                            message.assistant().get().toolCalls().get().map { tool ->
                                mapOf(
                                    "id" to tool.id(),
                                    "function" to
                                        mapOf(
                                            "name" to tool.function().name(),
                                            "arguments" to tool.function().arguments(),
                                        ),
                                )
                            }
                        Triple("assistant", "gen_ai.assistant.system", mapOf("tool_calls" to tools))
                    }
                    message.isTool() -> {
                        Triple("tool", "gen_ai.tool.message", mapOf("id" to message.tool().get().toolCallId(), "content" to message.tool().get().content()))
                    }
                    message.isSystem() && message.system().isPresent ->
                        Triple("system", "gen_ai.system.message", message.system().get().content())
                    message.isDeveloper() && message.developer().isPresent ->
                        Triple("system", "gen_ai.system.message", message.developer().get().content())
                    else -> null
                } ?: return@forEach

            val eventData =
                mapOf(
                    "gen_ai.system" to metadata.genAISystem,
                    "role" to role,
                    "content" to content,
                )
            observation.event(
                Observation.Event.of(eventName, mapper.writeValueAsString(eventData)),
            )
        }
    }

    private fun emitModelOutputEvents(
        observation: Observation,
        chatCompletion: ChatCompletion,
        metadata: CreateResponseMetadataInput,
    ) {
        chatCompletion.choices().forEach { choice ->
            if (choice.message().content().isPresent) {
                val eventData =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "role" to "assistant",
                        "content" to choice.message().content().get(),
                    )
                observation.event(
                    Observation.Event.of(
                        "gen_ai.choice",
                        jsonMapper().writeValueAsString(eventData),
                    ),
                )
            }

            val toolCalls = mutableListOf<Any>()
            if (choice.finishReason().asString() == "tool_calls") {
                val toolCalls =
                    choice.message().toolCalls().get().map { tool ->
                        mapOf(
                            "id" to tool.id(),
                            "type" to "function",
                            "function" to
                                mapOf(
                                    "name" to tool.function().name(),
                                    "arguments" to tool.function().arguments(),
                                ),
                        )
                    }
                val eventData =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "finish_reason" to choice.finishReason().asString(),
                        "index" to choice.index(),
                        "tool_calls" to toolCalls,
                    )
                observation.event(
                    Observation.Event.of("gen_ai.choice", jsonMapper().writeValueAsString(eventData)),
                )
            }
        }
    }

    // New helper function to set observation attributes
    private fun setAllObservationAttributes(
        observation: Observation,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "chat")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SYSTEM, metadata.genAISystem ?: "not_available")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OUTPUT_TYPE, "text")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_MODEL, chatCompletion.model())
        observation.highCardinalityKeyValue(GenAIObsAttributes.RESPONSE_ID, chatCompletion.id())

        params.temperature().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TEMPERATURE, it.toString()) }
        params.maxOutputTokens().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_MAX_TOKENS, it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TOP_P, it.toString()) }

        chatCompletion.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_INPUT_TOKENS, usage.promptTokens().toString())
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_OUTPUT_TOKENS, usage.completionTokens().toString())
        }

        setFinishReasons(observation, chatCompletion)
    }

    // Updated finish reasons function using observation instead of span
    private fun setFinishReasons(
        observation: Observation,
        chatCompletion: ChatCompletion,
    ) {
        val finishReasons =
            chatCompletion
                .choices()
                .mapNotNull {
                    it
                        .finishReason()
                        .value()
                        ?.name
                        ?.lowercase()
                }.distinct()
        if (finishReasons.isNotEmpty()) {
            observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_FINISH_REASONS, finishReasons.joinToString(","))
        }
    }

    // New helper function using Micrometer Observation
    private fun <T> withClientObservation(block: (Observation) -> T): T {
        val observation = Observation.createNotStarted("open.responses.create", observationRegistry)
        observation.start()
        return try {
            block(observation)
        } catch (e: Exception) {
            observation.error(e)
            observation.lowCardinalityKeyValue("error.type", "${e.javaClass}")
            throw e
        } finally {
            observation.stop()
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

    fun recordTokenUsage(
        metadata: CreateResponseMetadataInput,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        tokenType: String,
        tokenCount: Long,
    ) {
        // Build a DistributionSummary with semantic tags
        val summaryBuilder =
            DistributionSummary
                .builder("gen_ai.client.token.usage")
                .description("Measures number of input and output tokens used")
                .baseUnit("token")
                .tags(
                    GenAIObsAttributes.OPERATION_NAME,
                    "chat",
                    GenAIObsAttributes.SYSTEM,
                    metadata.genAISystem,
                    "gen_ai.token.type",
                    tokenType,
                )

        // Add optional tags if provided
        params.model().let { summaryBuilder.tag(GenAIObsAttributes.REQUEST_MODEL, it.value().name) }
        chatCompletion.model().let { summaryBuilder.tag(GenAIObsAttributes.RESPONSE_MODEL, it) }
        summaryBuilder.tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")

        // Register the summary
        val summary = summaryBuilder.register(meterRegistry)
        // Record the token usage count
        summary.record(tokenCount.toDouble())
    }

    fun <T> withTimer(
        genAiSystem: String,
        metadata: CreateResponseMetadataInput,
        params: ResponseCreateParams,
        block: () -> T,
    ): T {
        // Build a Timer with semantic tags
        val timerBuilder =
            Timer
                .builder(GenAIObsAttributes.OPERATION_DURATION)
                .description("GenAI operation duration")
                .tags(GenAIObsAttributes.OPERATION_NAME, "chat", GenAIObsAttributes.SYSTEM, metadata.genAISystem)

        // Add optional tags if provided
        params.model().let { timerBuilder.tag(GenAIObsAttributes.REQUEST_MODEL, it.value().name) }
        params.model().let { timerBuilder.tag(GenAIObsAttributes.RESPONSE_MODEL, it.value().name) } // for now assuming request and response model to be same.
        timerBuilder.tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")

        // Register the timer
        val timer = timerBuilder.register(meterRegistry)
//
//        // Start the timer sample
        val sample = Timer.start(meterRegistry)
        try {
            return block()
        } catch (ex: Exception) {
            // Optionally, you could record error details via additional tags or error metrics
            timerBuilder.tag(GenAIObsAttributes.ERROR_TYPE, "${ex.javaClass}")
            throw ex
        } finally {
            sample.stop(timer)
        }
    }

    /**
     * Creates a streaming completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     *
     * @param initialParams Parameters for creating the completion
     * @return Flow of ServerSentEvents containing response chunks
     */
    fun createCompletionStream(
        client: OpenAIClient,
        initialParams: ResponseCreateParams,
    ): Flow<ServerSentEvent<String>> {
        logger.debug { "Creating streaming completion with model: ${initialParams.model()}" }
        return streamingService.createCompletionStream(client, initialParams)
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
                                .fileName(inputFile._filename())
                                .build(),
                        ).build(),
                )
            }

            else -> throw IllegalArgumentException("Unsupported input type")
        }
    }
