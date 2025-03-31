package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
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
                val chatCompletions =
                    withTimer("open.responses.create", metadata, params) {
                        client.chat().completions().create(
                            parameterConverter.prepareCompletion(params),
                        )
                    }

                logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }

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

    // New helper function to set observation attributes
    private fun setAllObservationAttributes(
        observation: Observation,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "chat")
        observation.lowCardinalityKeyValue("gen_ai.system", metadata.modelProvider ?: "not_available")
        observation.lowCardinalityKeyValue("gen_ai.output.type", "text")
        observation.lowCardinalityKeyValue("gen_ai.request.model", params.model().toString())
        observation.lowCardinalityKeyValue("gen_ai.response.model", chatCompletion.model())
        observation.highCardinalityKeyValue("gen_ai.response.id", chatCompletion.id())

        params.temperature().ifPresent { observation.highCardinalityKeyValue("gen_ai.request.temperature", it.toString()) }
        params.maxOutputTokens().ifPresent { observation.highCardinalityKeyValue("gen_ai.request.max_tokens", it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue("gen_ai.request.top_p", it.toString()) }

        chatCompletion.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue("gen_ai.usage.input_tokens", usage.promptTokens().toString())
            observation.highCardinalityKeyValue("gen_ai.usage.output_tokens", usage.completionTokens().toString())
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
            observation.lowCardinalityKeyValue("gen_ai.response.finish_reasons", finishReasons.joinToString(","))
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
                    "gen_ai.operation.name",
                    "chat",
                    "gen_ai.system",
                    metadata.modelProvider,
                    "gen_ai.token.type",
                    tokenType,
                )

        // Add optional tags if provided
        params.model().let { summaryBuilder.tag("gen_ai.request.model", it.value().name) }
        chatCompletion.model().let { summaryBuilder.tag("gen_ai.response.model", it) }
        summaryBuilder.tag("server.address", metadata.modelProviderAddress ?: "not_available")

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
                .builder("gen_ai.client.operation.duration")
                .description("GenAI operation duration")
                .tags("gen_ai.operation.name", "chat", "gen_ai.system", metadata.modelProvider)

        // Add optional tags if provided
        params.model().let { timerBuilder.tag("gen_ai.request.model", it.value().name) }
        params.model().let { timerBuilder.tag("gen_ai.response.model", it.value().name) } // for now assuming request and response model to be same.
        timerBuilder.tag("server.address", metadata.modelProviderAddress ?: "not_available")

        // Register the timer
        val timer = timerBuilder.register(meterRegistry)
//
//        // Start the timer sample
        val sample = Timer.start(meterRegistry)
        try {
            return block()
        } catch (ex: Exception) {
            // Optionally, you could record error details via additional tags or error metrics
            timerBuilder.tag("error.type", "${ex.javaClass}")
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
