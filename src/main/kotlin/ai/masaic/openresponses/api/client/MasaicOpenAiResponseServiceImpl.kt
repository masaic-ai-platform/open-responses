package ai.masaic.openresponses.api.client

import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.responses.*
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
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
     *
     * @param params Parameters for creating the response
     * @return Response object containing completion data
     */
    fun create(
        client: OpenAIClient,
        params: ResponseCreateParams,
    ): Response {
        logger.debug { "Creating completion with model: ${params.model()}" }
        try {
            // Convert params to OpenAI format and create the chat completion
            val chatCompletions = client.chat().completions().create(parameterConverter.prepareCompletion(params))
            logger.debug { "Received chat completion with ID: ${chatCompletions.id()}" }

            if (!chatCompletions.choices().any {
                    it
                        .finishReason()
                        .value()
                        .name
                        .lowercase() == "tool_calls"
                }
            ) {
                logger.info { "No tool calls detected, returning direct response" }
                return chatCompletions.toResponse(params)
            }

            // Process any tool calls in the response
            logger.debug { "Processing tool calls from completion response" }
            val responseInputItems = toolHandler.handleMasaicToolCall(chatCompletions, params)

            // Rebuild the params with the updated input items for the follow-up request
            val newParams =
                params
                    .toBuilder()
                    .input(ResponseCreateParams.Input.ofResponse(responseInputItems))
                    .build()

            // Check if we need to make follow-up requests for tool calls
            if (newParams
                    .input()
                    .asResponse()
                    .filter { it.isFunctionCall() }
                    .size >
                newParams
                    .input()
                    .asResponse()
                    .filter { it.isFunctionCallOutput() }
                    .size
            ) {
                logger.info { "Some function calls without outputs, returning current response" }
                return chatCompletions.toResponse(newParams)
            } else if (responseInputItems.filter { it.isFunctionCall() }.size > getAllowedMaxToolCalls()) {
                val errorMsg = "Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable."
                logger.error { errorMsg }
                throw IllegalArgumentException(errorMsg)
            }

            // Recursively make the next request with the updated params
            logger.debug { "Making recursive completion request with updated parameters" }
            return create(client, newParams)
        } catch (e: Exception) {
            logger.error(e) { "Error creating completion: ${e.message}" }
            throw e
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
