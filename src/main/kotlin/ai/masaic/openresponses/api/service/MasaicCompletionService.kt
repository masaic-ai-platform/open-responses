package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.client.CompletionStore
import ai.masaic.openresponses.api.client.MasaicOpenAiCompletionServiceImpl
import ai.masaic.openresponses.api.extensions.isImageContent
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.core.http.Headers
import com.openai.core.http.QueryParams
import com.openai.credential.BearerTokenCredential
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.ResponseFormatText
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Service for interacting with the OpenAI API to create and manage chat completions.
 */
@Service
class MasaicCompletionService(
    private val openAICompletionService: MasaicOpenAiCompletionServiceImpl,
    private val completionStore: CompletionStore,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
        const val MODEL_DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_TIMEOUT_SECONDS = 120L

        private val PROVIDER_BASE_URLS =
            mapOf(
                "openai" to "https://api.openai.com/v1",
                "claude" to "https://api.anthropic.com/v1",
                "groq" to "https://api.groq.com/openai/v1",
                "anthropic" to "https://api.anthropic.com/v1",
                "togetherai" to "https://api.together.xyz/v1",
                "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/",
                "google" to "https://generativelanguage.googleapis.com/v1beta/openai/",
                "deepseek" to "https://api.deepseek.com",
                "ollama" to "http://localhost:11434/v1",
                "xai" to "https://api.x.ai/v1",
            )

        /**
         * Gets an environment variable - extracted for testability
         *
         * @param name Name of the environment variable
         * @return The value of the environment variable or null if not found
         */
        fun getEnvVar(name: String): String? = System.getenv(name)

        /**
         * Gets the API base URL to use for requests.
         * Supports both x-model-provider header and url@model or provider@model format in the model field
         *
         * @param headers HTTP headers containing potential x-model-provider
         * @param modelName The model name which may contain url@model or provider@model format
         * @return The API base URL as URI
         */
        @JvmStatic
        fun getApiBaseUri(
            headers: MultiValueMap<String, String>,
            modelName: String?,
        ): URI {
            // First check if the model contains url@model or provider@model format
            if (modelName?.contains("@") == true) {
                val parts = modelName.split("@", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    // Check if the first part is a URL
                    return if (parts[0].startsWith("http://") || parts[0].startsWith("https://")) {
                        URI(parts[0])
                    } else {
                        // Check if it's a known provider name
                        val providerUrl = PROVIDER_BASE_URLS[parts[0].lowercase()]
                        if (providerUrl != null) {
                            URI(providerUrl)
                        } else {
                            // If provider not recognized, fall back to default behavior
                            getDefaultApiUri(headers)
                        }
                    }
                }
            }

            // Fall back to x-model-provider header based logic
            return getDefaultApiUri(headers)
        }

        /**
         * Gets the default API URI based on x-model-provider header or environment variable
         *
         * @param headers HTTP headers containing potential x-model-provider
         * @return The default API URI
         */
        @JvmStatic
        fun getDefaultApiUri(headers: MultiValueMap<String, String>): URI {
            val provider = headers.getFirst("x-model-provider")?.lowercase()
            return when {
                provider != null -> URI(PROVIDER_BASE_URLS[provider] ?: MODEL_DEFAULT_BASE_URL)
                else -> URI(System.getenv(OPENAI_BASE_URL) ?: MODEL_DEFAULT_BASE_URL)
            }
        }
    }

    @Value("\${api.request.timeout:$DEFAULT_TIMEOUT_SECONDS}")
    private val requestTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS

    /**
     * Creates a completion from the OpenAI API with enhanced error handling and timeout.
     *
     * @param request The request body containing parameters for the completion
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return The chat completion from the OpenAI API
     * @throws CompletionTimeoutException If the request exceeds the configured timeout
     * @throws CompletionProcessingException If there is an error processing the completion
     */
    suspend fun createCompletion(
        request: CreateCompletionRequest,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): ChatCompletion {
        logger.info { "Creating completion with, model: ${request.model}" }

        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers, request.model)
        val messages = request.parseMessages(objectMapper)

        return try {
            val timeoutMillis = Duration.ofSeconds(requestTimeoutSeconds).toMillis()
            withTimeout(timeoutMillis) {
                val params = createChatCompletionParams(request, messages, headerBuilder, queryBuilder)
                val metadata = instrumentationMetadataInput(headers, request)
                
                val completion = openAICompletionService.create(client, params, metadata)
                completion
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error { "Request timed out after $requestTimeoutSeconds seconds" }
            throw CompletionTimeoutException("Request timed out after $requestTimeoutSeconds seconds")
        } catch (e: TimeoutException) {
            logger.error { "Request timed out after $requestTimeoutSeconds seconds" }
            throw CompletionTimeoutException("Request timed out after $requestTimeoutSeconds seconds")
        } catch (e: CancellationException) {
            logger.warn { "Request was cancelled" }
            throw e // Let cancellation exceptions propagate
        }
    }

    /**
     * Creates a streaming completion from the OpenAI API with enhanced error handling.
     *
     * @param request The request body containing parameters for the completion
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return A flow of server-sent events containing the streaming completion
     */
    suspend fun createStreamingCompletion(
        request: CreateCompletionRequest,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Creating streaming completion with, model: ${request.model}" }

        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers, request.model)
        val messages = request.parseMessages(objectMapper)

        try {
            val params = createChatCompletionParams(request, messages, headerBuilder, queryBuilder)
            val metadata = instrumentationMetadataInput(headers, request)
            
            return openAICompletionService.createCompletionStream(client, params, metadata)
        } catch (e: Exception) {
            logger.error { "Failed to create streaming completion" }
            throw e
        }
    }

    /**
     * Retrieves a completion by ID.
     *
     * @param completionId The ID of the completion to retrieve
     * @return The completion from the store
     * @throws CompletionNotFoundException If the completion cannot be found
     */
    suspend fun getCompletion(completionId: String): ChatCompletion =
        try {
            completionStore.getCompletion(completionId) ?: throw CompletionNotFoundException("Completion not found with ID: $completionId")
        } catch (e: Exception) {
            when (e) {
                is CompletionNotFoundException -> throw e
                else -> throw CompletionProcessingException("Error retrieving completion: ${e.message}")
            }
        }

    /**
     * Creates a headers builder with authorization headers.
     *
     * @param headers The HTTP headers to include
     * @return A headers builder
     */
    private fun createHeadersBuilder(headers: MultiValueMap<String, String>): Headers.Builder {
        val headerBuilder = Headers.builder()
        headers
            .filter { it.key.equals("Authorization", ignoreCase = true) }
            .forEach { (key, value) -> headerBuilder.put(key, value) }
        return headerBuilder
    }

    /**
     * Creates a query parameters builder.
     *
     * @param queryParams The query parameters to include
     * @return A query parameters builder
     */
    private fun createQueryParamsBuilder(queryParams: MultiValueMap<String, String>): QueryParams.Builder {
        val queryBuilder = QueryParams.builder()
        queryParams.forEach { (key, value) -> queryBuilder.put(key, value) }
        return queryBuilder
    }

    /**
     * Creates an OpenAI client with the appropriate credentials and base URL.
     *
     * @param headers The HTTP headers containing authorization information
     * @param modelName The model name for base URL determination
     * @return An OpenAI client
     * @throws IllegalArgumentException If the API key is missing
     */
    private fun createClient(
        headers: MultiValueMap<String, String>,
        modelName: String,
    ): OpenAIClient {
        val authHeader =
            headers.getFirst("Authorization") ?: headers.getFirst("authorization")
                ?: throw IllegalArgumentException("api-key is missing.")

        val credential =
            BearerTokenCredential.create {
                authHeader.split(" ").getOrNull(1) ?: throw IllegalArgumentException("api-key is missing.")
            }

        return OpenAIOkHttpClient
            .builder()
            .credential(credential)
            .baseUrl(getApiBaseUri(headers, modelName).toURL().toString())
            .build()
    }

    /**
     * Creates ChatCompletion parameters from the request.
     */
    private fun createChatCompletionParams(
        request: CreateCompletionRequest,
        messages: List<ChatCompletionMessageParam>,
        headerBuilder: Headers.Builder,
        queryBuilder: QueryParams.Builder,
    ): ChatCompletionCreateParams {
        val builder =
            ChatCompletionCreateParams
                .builder()
                .messages(removeImageBody(messages))
                .additionalHeaders(headerBuilder.build())
                .additionalQueryParams(queryBuilder.build())

        // Extract model name from request
        val modelName = request.model.toString()

        // If model contains url@model format, update the model name to just the model part
        if (modelName.contains("@") == true) {
            val parts = modelName.split("@", limit = 2)
            if (parts.size == 2) {
                builder.model(parts[1])
            }
        } else {
            builder.model(modelName)
        }

        request.frequency_penalty?.let { builder.frequencyPenalty(it) }
        request.logit_bias?.let { builder.logitBias(objectMapper.convertValue(it, ChatCompletionCreateParams.LogitBias::class.java)) }
        request.logprobs?.let { builder.logprobs(it) }
        request.top_logprobs?.let { builder.topLogprobs(it.toLong()) }
        request.max_tokens?.let { builder.maxCompletionTokens(it.toLong()) }
        request.n?.let { builder.n(it.toLong()) }
        request.presence_penalty?.let { builder.presencePenalty(it) }
        request.metadata?.let { builder.metadata(it) }
        request.response_format?.let {
            val type = it["type"] ?: "text"
            when (type) {
                "text" ->
                    builder.responseFormat(
                        ChatCompletionCreateParams.ResponseFormat.ofText(
                            ResponseFormatText
                                .builder()
                                .type(
                                    JsonValue.from(type),
                                ).build(),
                        ),
                    )
                "json_object" ->
                    builder.responseFormat(
                        ChatCompletionCreateParams.ResponseFormat.ofJsonObject(
                            ResponseFormatJsonObject.builder().build(),
                        ),
                    )
                "json_schema" ->
                    builder.responseFormat(
                        ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(
                            ResponseFormatJsonSchema
                                .builder()
                                .jsonSchema(
                                    JsonValue.from(it["json_schema"] ?: throw IllegalArgumentException("json_schema is required")),
                                ).type(JsonValue.from(type))
                                .build(),
                        ),
                    )
                else -> throw IllegalArgumentException("Invalid response format type: $type")
            }
        }
        request.seed?.let { builder.seed(it) }
        request.stop?.let {
            builder.stop(
                objectMapper.convertValue(
                    it,
                    ChatCompletionCreateParams.Stop::class.java,
                ),
            )
        }
        if (request.stream) {
            builder.additionalBodyProperties(mapOf("stream" to JsonValue.from(true)))
            request.stream_options?.let {
                builder.streamOptions(
                    ChatCompletionStreamOptions.builder().includeUsage(it["include_usage"] as? Boolean == true).build(),
                )
            }
        }

        request.temperature?.let { builder.temperature(it) }
        request.top_p?.let { builder.topP(it) }
        request.store.let { builder.store(it) }
        
        // Handle tools if provided
        if (!request.tools.isNullOrEmpty()) {
            val tools =
                request.tools
                    ?.map { tool ->
                        val completionTool =
                            objectMapper.convertValue(
                                tool,
                                ChatCompletionTool::class.java,
                            )
                        completionTool.toBuilder().type(JsonValue.from("function")).build()
                    }?.toList() ?: emptyList()
            builder.tools(tools)
        }
        
        // Handle tool_choice if provided
        request.tool_choice?.let {
            val toolChoice =
                objectMapper.convertValue(
                    it,
                    ChatCompletionToolChoiceOption::class.java,
                )
            builder.toolChoice(toolChoice)
        }
        
        request.user?.let { builder.user(it) }
        
        return builder.build()
    }

    /**
     * This function takes a list of ChatCompletionMessageParam objects and removes the bodies of all the messages
     * that contain an image generation function. The bodies of the messages are replaced with "<FORMAT>...".
     *
     * @param messages The list of messages to process
     * @return A new list of messages, with the bodies of the image generation functions removed
     */
    private fun removeImageBody(messages: List<ChatCompletionMessageParam>): List<ChatCompletionMessageParam> {
        val imageGenerationFunctions =
            messages
                .filterIsInstance<ChatCompletionMessageToolCall>()
                .filter { it.function().name() == "image-generation" }
                .map { it.id() }

        val newMessages = mutableListOf<ChatCompletionMessageParam>()

        for (message in messages) {
            if (message.isTool()) {
                if (imageGenerationFunctions.contains(message.asTool().toolCallId())) {
                    // Check if the tool content is an image using the new detection logic
                    val toolContent = message.asTool().content()
                    if (toolContent.isText()) {
                        val imageInfo = isImageContent(toolContent.asText())
                        val replacementText = if (imageInfo.isImage) "<${imageInfo.format}>..." else "<image>..."

                        newMessages.add(
                            ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam
                                    .builder()
                                    .toolCallId(message.asTool().toolCallId())
                                    .content(replacementText)
                                    .build(),
                            ),
                        )
                    } else if (toolContent.isArrayOfContentParts()) {
                        val contentParts = toolContent.asArrayOfContentParts()
                        val newContentParts = mutableListOf<ChatCompletionContentPartText>()
                        for (contentPart in contentParts) {
                            val imageInfo = isImageContent(contentPart.text())
                            val replacementText = if (imageInfo.isImage) "<${imageInfo.format}>..." else contentPart.text()
                            newContentParts.add(
                                ChatCompletionContentPartText
                                    .builder()
                                    .text(replacementText)
                                    .build(),
                            )
                        }
                        newMessages.add(
                            ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam
                                    .builder()
                                    .toolCallId(message.asTool().toolCallId())
                                    .content(ChatCompletionToolMessageParam.Content.ofArrayOfContentParts(newContentParts))
                                    .build(),
                            ),
                        )
                    } else {
                        // For other content types, add default replacement
                        newMessages.add(
                            ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam
                                    .builder()
                                    .toolCallId(message.asTool().toolCallId())
                                    .content("<image>...")
                                    .build(),
                            ),
                        )
                    }
                } else {
                    newMessages.add(message)
                }
            } else if (message.isAssistant()) {
                var imageFormat = "output_image"
                
                // Check the old detection method for backward compatibility
                if (message.asAssistant()._additionalProperties().containsKey("type") &&
                    message
                        .asAssistant()
                        ._additionalProperties()["type"]
                        .toString() == "output_image"
                ) {
                    newMessages.add(
                        ChatCompletionMessageParam
                            .ofAssistant(
                                ChatCompletionAssistantMessageParam
                                    .builder()
                                    .content("<$imageFormat>...")
                                    .build(),
                            ),
                    )
                    continue
                }
                
                // Check the actual content using the new detection logic
                val assistantContent = message.asAssistant().content()
                if (assistantContent.isPresent) {
                    val textContent = assistantContent.get()
                    if (textContent.isText()) {
                        val imageInfo = isImageContent(textContent.asText())
                        if (imageInfo.isImage) {
                            imageFormat = imageInfo.format
                            newMessages.add(
                                ChatCompletionMessageParam
                                    .ofAssistant(
                                        ChatCompletionAssistantMessageParam
                                            .builder()
                                            .content("<$imageFormat>...")
                                            .build(),
                                    ),
                            )
                            continue
                        }
                    } else if (textContent.isArrayOfContentParts()) {
                        val contentParts = textContent.asArrayOfContentParts()
                        val newContentParts = mutableListOf<ChatCompletionRequestAssistantMessageContentPart>()
                        var hasImageReplacement = false
                        
                        for (contentPart in contentParts) {
                            if (contentPart.isText() && contentPart.asText().text().isNotBlank()) {
                                val imageInfo = isImageContent(contentPart.asText().text())
                                if (imageInfo.isImage) {
                                    newContentParts.add(
                                        ChatCompletionRequestAssistantMessageContentPart
                                            .ofText(
                                                ChatCompletionContentPartText
                                                    .builder()
                                                    .text(
                                                        "<${imageInfo.format}>...",
                                                    ).build(),
                                            ),
                                    )
                                    hasImageReplacement = true
                                } else {
                                    newContentParts.add(contentPart)
                                }
                            } else {
                                newContentParts.add(contentPart)
                            }
                        }

                        // Only create new message if we had replacements, otherwise use original
                        if (hasImageReplacement) {
                            newMessages.add(
                                ChatCompletionMessageParam
                                    .ofAssistant(
                                        ChatCompletionAssistantMessageParam
                                            .builder()
                                            .content(ChatCompletionAssistantMessageParam.Content.ofArrayOfContentParts(newContentParts))
                                            .build(),
                                    ),
                            )
                        } else {
                            newMessages.add(message)
                        }
                        continue
                    }
                }
                newMessages.add(message)
            } else {
                newMessages.add(message)
            }
        }

        return newMessages
    }

    private fun instrumentationMetadataInput(
        headers: MultiValueMap<String, String>,
        request: CreateCompletionRequest,
    ): InstrumentationMetadataInput {
        val modelName = request.model
        val parts = modelName.split("@", limit = 2)

        var genAiSystem = "UNKNOWN"
        var actualModelName = modelName
        if (parts.size == 2) {
            if (!(parts[0].startsWith("http://") || parts[0].startsWith("https://"))) {
                genAiSystem = parts[0]
            }
            actualModelName = parts[1]
        }

        val url = MasaicResponseService.getApiBaseUri(headers, request.model)
        return InstrumentationMetadataInput(genAiSystem, actualModelName, url.host, url.port.toString())
    }
}

/**
 * Exception thrown when a completion cannot be found.
 *
 * @param message The error message
 */
class CompletionNotFoundException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when a request times out.
 *
 * @param message The error message
 */
class CompletionTimeoutException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when there is an error processing a completion.
 *
 * @param message The error message
 */
class CompletionProcessingException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when there is an error in a streaming completion.
 *
 * @param message The error message
 * @param cause The cause of the exception
 */
class CompletionStreamingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) 
