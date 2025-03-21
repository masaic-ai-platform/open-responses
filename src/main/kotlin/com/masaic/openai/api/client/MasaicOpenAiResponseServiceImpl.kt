package com.masaic.openai.api.client

import com.masaic.openai.tool.ToolService
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
import org.springframework.http.codec.ServerSentEvent

/**
 * Implementation of ResponseService for Masaic OpenAI API client.
 * This service handles communication with OpenAI's API for chat completions
 * and provides methods to create, retrieve, and stream responses.
 *
 * This implementation follows the Single Responsibility Principle by delegating
 * specific tasks to specialized helper classes.
 *
 * @param client The OpenAI client used to make API requests
 * @param toolService The service for managing tool operations
 */
class MasaicOpenAiResponseServiceImpl(
    private val client: OpenAIClient,
    private val toolService: ToolService
) : ResponseService {

    // Helper objects for SRP compliance
    private val parameterConverter = MasaicParameterConverter()
    private val toolHandler = MasaicToolHandler(toolService)
    private val streamingService = MasaicStreamingService(client, toolHandler, parameterConverter)

    /**
     * Not implemented: Returns a version of this service that includes raw HTTP response data.
     */
    override fun withRawResponse(): ResponseService.WithRawResponse {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Returns the input items service for this response service.
     */
    override fun inputItems(): InputItemService {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Creates a new completion response based on provided parameters.
     *
     * @param params Parameters for creating the response
     * @param requestOptions Options for the HTTP request
     * @return Response object containing completion data
     */
    override fun create(
        params: ResponseCreateParams,
        requestOptions: RequestOptions
    ): Response {
        // Convert params to OpenAI format and create the chat completion
        val chatCompletions = client.chat().completions().create(parameterConverter.prepareCompletion(params))
        
        // Process any tool calls in the response
        val responseInputItems = toolHandler.handleMasaicToolCall(chatCompletions, params)
        
        // Check if we need to make follow-up requests for tool calls
        if (!responseInputItems.any { it.isFunctionCall() } || 
            (responseInputItems.filter { it.isFunctionCall() }.size > responseInputItems.filter { it.isFunctionCallOutput() }.size)) {
            return chatCompletions.toResponse(params)
        } else if (responseInputItems.filter { it.isFunctionCall() }.size > getAllowedMaxToolCalls()) {
            throw IllegalArgumentException("Too many tool calls. Increase the limit by setting MASAIC_MAX_TOOL_CALLS environment variable.")
        }

        // Rebuild the params with the updated input items for the follow-up request
        val newParams = params.toBuilder()
            .input(ResponseCreateParams.Input.ofResponse(responseInputItems))
            .build()

        // Recursively make the next request with the updated params
        return create(newParams, requestOptions)
    }

    /**
     * Creates a streaming completion that emits ServerSentEvents.
     * This allows for real-time response processing.
     *
     * @param initialParams Parameters for creating the completion
     * @return Flow of ServerSentEvents containing response chunks
     */
    fun createCompletionStream(
        initialParams: ResponseCreateParams
    ): Flow<ServerSentEvent<String>> {
        return streamingService.createCompletionStream(initialParams)
    }
    
    /**
     * Not implemented: Creates a streaming response.
     */
    override fun createStreaming(
        params: ResponseCreateParams,
        requestOptions: RequestOptions
    ): StreamResponse<ResponseStreamEvent> {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Retrieves a specific response by ID.
     */
    override fun retrieve(
        params: ResponseRetrieveParams,
        requestOptions: RequestOptions
    ): Response {
        throw UnsupportedOperationException("Not yet implemented")
    }

    /**
     * Not implemented: Deletes a response by ID.
     */
    override fun delete(
        params: ResponseDeleteParams,
        requestOptions: RequestOptions
    ) {
        throw UnsupportedOperationException("Not yet implemented")
    }
    
    /**
     * Gets the maximum allowed tool calls from environment or default.
     */
    private fun getAllowedMaxToolCalls(): Int {
        return System.getenv("MASAIC_MAX_TOOL_CALLS")?.toInt() ?: 10
    }
}

/**
 * Prepares user content from a message.
 *
 * @param message The message to extract content from
 * @return List of ChatCompletionContentPart objects
 */
private fun prepareUserContent(message: ResponseInputItem.Message): List<ChatCompletionContentPart> =
    prepareUserContent(message.content())

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
                    ChatCompletionContentPartText.builder().text(
                        inputText.text()
                    ).build()
                )
            }

            content.isInputImage() -> {
                val inputImage = content.asInputImage()
                ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder().type(JsonValue.from("image_url")).imageUrl(
                        ChatCompletionContentPartImage.ImageUrl.builder()
                            .url(inputImage._imageUrl())
                            .detail(
                                ChatCompletionContentPartImage.ImageUrl.Detail.of(
                                    inputImage.detail().value().name.lowercase()
                                )
                            )
                            .putAllAdditionalProperties(inputImage._additionalProperties())
                            .build()
                    ).build()
                )
            }

            content.isInputFile() -> {
                val inputFile = content.asInputFile()
                ChatCompletionContentPart.ofFile(
                    ChatCompletionContentPart.File.builder().type(JsonValue.from("file")).file(
                        ChatCompletionContentPart.File.FileObject.builder()
                            .fileData(inputFile._fileData())
                            .fileId(inputFile._fileId())
                            .fileName(inputFile._filename()).build()
                    ).build()
                )
            }

            else -> throw IllegalArgumentException("Unsupported input type")
        }
    }