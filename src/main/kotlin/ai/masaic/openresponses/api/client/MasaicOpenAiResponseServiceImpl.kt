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
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
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
    private val openTelemetry: OpenTelemetry,
) : ResponseService {
    private val logger = KotlinLogging.logger {}
    private val tracer: Tracer = openTelemetry.getTracer("ai.masaic.openresponses.api.client")

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
        val responseOrCompletions = withClientSpan { span ->
            logger.debug { "Creating completion with model: ${params.model()}" }
            val chatCompletions = client.chat().completions().create(
                parameterConverter.prepareCompletion(params),
            )
            logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }

            // Set any relevant span attributes
            setAllSpanAttributes(span, chatCompletions, params, metadata)

            // If no tool calls => return direct response
            if (!hasToolCalls(chatCompletions)) {
                logger.info { "No tool calls detected, returning direct response" }
                return@withClientSpan chatCompletions.toResponse(params)
            }

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

    /**
     * Wraps the given [block] execution in a CLIENT span named [spanName].
     * Ensures the span is ended and errors are recorded if an exception occurs.
     */
    private fun withClientSpan(
        block: (Span) -> Any,
    ): Any {
        val spanName = "open_responses_create"
        val span =
            tracer
                .spanBuilder(spanName)
                .setParent(Context.current()) // same parent context as your request
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()

        return try {
            block(span)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR)
            span.setAttribute(GenAiAttributes.ERROR_TYPE, "${e.javaClass.simpleName}; ${e.message}")
            logger.error(e) { "Error in span '$spanName': ${e.message}" }
            throw e
        } finally {
            span.setStatus(StatusCode.OK)
            span.end()
        }
    }

    /**
     * Creates a completion using an existing span context.
     * This is where the main logic resides (including recursion).
     */
    private fun createWithSpan(
        client: OpenAIClient,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
        span: Span,
    ): CreateResponseResult {
        logger.debug { "Creating completion with model: ${params.model()}" }

        // Call the OpenAI client
        val chatCompletions =
            client.chat().completions().create(
                parameterConverter.prepareCompletion(params),
            )
        logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }

        // Set all span attributes based on request params and response
        setAllSpanAttributes(span, chatCompletions, params, metadata)

        // Check for tool calls
        if (!hasToolCalls(chatCompletions)) {
            logger.info { "No tool calls detected, returning direct response" }
            return CreateResponseResult(chatCompletions.toResponse(params), chatCompletions = chatCompletions)
        }

        logger.debug { "Making recursive completion request with updated parameters" }

        // Make a new call to create(...) so we get a fresh sibling span
        // (i.e. each recursion is recorded in its own span)
        return CreateResponseResult(chatCompletions = chatCompletions)
//        return create(client, updatedParams, metadata)
    }

    data class CreateResponseResult(val response: Response?=null, val chatCompletions: ChatCompletion?=null)

    /**
     * Sets all applicable attributes on the span based on request parameters and chat completion response.
     * Consolidates all attribute setting in one place for better maintainability.
     */
    private fun setAllSpanAttributes(
        span: Span,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        // Basic information
        span.setAttribute(GenAiAttributes.OPERATION_NAME, "chat")
        span.setAttribute(GenAiAttributes.SYSTEM, metadata.modelProvider ?: "not_available")
        span.setAttribute(GenAiAttributes.OUTPUT_TYPE, "text")

        // Model information
        span.setAttribute(GenAiAttributes.REQUEST_MODEL, params.model().toString())
        span.setAttribute(GenAiAttributes.RESPONSE_MODEL, chatCompletion.model())
        span.setAttribute(GenAiAttributes.RESPONSE_ID, chatCompletion.id())

        // Request parameters - handle all optionals correctly
        // Temperature - optional
        params.temperature().let {
            if (it.isPresent) span.setAttribute(GenAiAttributes.REQUEST_TEMPERATURE, it.get())
        }

        params.maxOutputTokens().let {
            if (it.isPresent) span.setAttribute(GenAiAttributes.REQUEST_MAX_TOKENS, it.get())
        }

        params.topP().let {
            if (it.isPresent) span.setAttribute(GenAiAttributes.REQUEST_TOP_P, it.get())
        }

        // Usage tokens
        chatCompletion.usage().ifPresent { usage ->
            span.setAttribute(GenAiAttributes.USAGE_INPUT_TOKENS, usage.promptTokens().toLong())
            span.setAttribute(GenAiAttributes.USAGE_OUTPUT_TOKENS, usage.completionTokens().toLong())
        }

        // Set finish reasons
        setFinishReasons(span, chatCompletion)
    }

    /**
     * Extracts finish reasons from the chat completions and sets them on the [span].
     */
    private fun setFinishReasons(
        span: Span,
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

        if (finishReasons.isEmpty()) return

        val attributes =
            Attributes
                .builder()
                .put(GenAiAttributes.RESPONSE_FINISH_REASONS, *finishReasons.toTypedArray())
                .build()

        // Because the attribute key is typed, we set it with a small type-safety workaround
        attributes.asMap().forEach { (key, value) ->
            if (key == GenAiAttributes.RESPONSE_FINISH_REASONS) {
                @Suppress("UNCHECKED_CAST")
                span.setAttribute(key as AttributeKey<Any>, value)
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
