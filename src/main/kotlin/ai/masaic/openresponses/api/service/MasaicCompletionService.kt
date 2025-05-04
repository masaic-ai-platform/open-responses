package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.client.MasaicOpenAiCompletionServiceImpl
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import com.fasterxml.jackson.core.type.TypeReference
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
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Service for interacting with the OpenAI API to create and manage chat completions.
 */
@Service
class MasaicCompletionService(
    private val openAICompletionService: MasaicOpenAiCompletionServiceImpl,
    // private val completionStore: CompletionStore,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val MODEL_BASE_URL = "MODEL_BASE_URL"
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
                else -> URI(System.getenv(MODEL_BASE_URL) ?: MODEL_DEFAULT_BASE_URL)
            }
        }
    }

    @Value("\${api.request.timeout:$DEFAULT_TIMEOUT_SECONDS}")
    private val requestTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS

    /**
     * Gets the trace ID from both headers and reactor context for proper tracing.
     *
     * @param headers HTTP headers
     * @return The trace ID or "unknown" if not found
     */
    private suspend fun getTraceId(headers: MultiValueMap<String, String>): String {
        // First check if we have it in the reactor context
        val contextTraceId = getTraceIdFromContext()
        if (contextTraceId != null && contextTraceId != "unknown") {
            return contextTraceId
        }

        // Fall back to headers if not in context
        return headers.getFirst("X-B3-TraceId")
            ?: headers.getFirst("X-Trace-ID")
            ?: "unknown"
    }

    /**
     * Retrieves trace ID from Reactor context if available
     */
    private suspend fun getTraceIdFromContext(): String? =
        try {
            coroutineContext[ReactorContext]?.context?.getOrEmpty<String>("traceId")?.orElse(null)
        } catch (e: Exception) {
            logger.debug { "Could not retrieve traceId from context: ${e.message}" }
            null
        }

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
        val traceId = getTraceId(headers)
        logger.info { "Creating completion with traceId: $traceId, model: ${request.model}" }

        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers, request.model)
        val messages = request.parseMessages(objectMapper)

        return try {
            val timeoutMillis = Duration.ofSeconds(requestTimeoutSeconds).toMillis()
            withTimeout(timeoutMillis) {
                val params = createChatCompletionParams(request, messages, headerBuilder, queryBuilder)
                val metadata = createMetadataInput(headers, request.model)
                
                val completion = openAICompletionService.create(client, params, metadata)
                // completionStore.storeCompletion(completion, messages)
                completion
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error { "Request timed out after $requestTimeoutSeconds seconds - traceId: $traceId" }
            throw CompletionTimeoutException("Request timed out after $requestTimeoutSeconds seconds")
        } catch (e: TimeoutException) {
            logger.error { "Request timed out after $requestTimeoutSeconds seconds - traceId: $traceId" }
            throw CompletionTimeoutException("Request timed out after $requestTimeoutSeconds seconds")
        } catch (e: CancellationException) {
            logger.warn { "Request was cancelled - traceId: $traceId" }
            throw e // Let cancellation exceptions propagate
        } catch (e: Exception) {
            logger.error(e) { "Error creating completion - traceId: $traceId" }
            throw CompletionProcessingException("Error processing completion: ${e.message}")
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
        val traceId = getTraceId(headers)
        logger.info { "Creating streaming completion with traceId: $traceId, model: ${request.model}" }

        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers, request.model)
        val messages = request.parseMessages(objectMapper)

        try {
            val params = createChatCompletionParams(request, messages, headerBuilder, queryBuilder)
            val metadata = createMetadataInput(headers, request.model)
            
            return openAICompletionService.createCompletionStream(client, params, metadata)
        } catch (e: Exception) {
            logger.error(e) { "Failed to create streaming completion - traceId: $traceId" }
            throw CompletionStreamingException("Failed to create streaming completion: ${e.message}", e)
        }
    }

    /**
     * Retrieves a completion by ID.
     *
     * @param completionId The ID of the completion to retrieve
     * @return The completion from the store
     * @throws CompletionNotFoundException If the completion cannot be found
     */
    /*suspend fun getCompletion(completionId: String): ChatCompletion =
        try {
            completionStore.getCompletion(completionId) ?: throw CompletionNotFoundException("Completion not found with ID: $completionId")
        } catch (e: Exception) {
            when (e) {
                is CompletionNotFoundException -> throw e
                else -> throw CompletionProcessingException("Error retrieving completion: ${e.message}")
            }
        }*/

    /**
     * Creates a headers builder with authorization headers.
     *
     * @param headers The HTTP headers to include
     * @return A headers builder
     */
    private fun createHeadersBuilder(headers: MultiValueMap<String, String>): Headers.Builder {
        val headerBuilder = Headers.builder()
        headers
            .filter { it.key == "Authorization" }
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
            headers.getFirst("Authorization")
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
                .messages(messages)
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
                                    JsonValue.from(it["schema"] ?: throw IllegalArgumentException("json_schema is required")),
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
        request.stream?.let { builder.additionalBodyProperties(mapOf("stream" to JsonValue.from(true))) }
        request.temperature?.let { builder.temperature(it) }
        request.top_p?.let { builder.topP(it) }
        
        // Handle tools if provided
        if (!request.tools.isNullOrEmpty()) {
            val typeReference = object : TypeReference<List<ChatCompletionTool>>() {}
            builder.tools(
                objectMapper.convertValue(
                    request.tools,
                    typeReference,
                ),
            )
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
     * Creates metadata input for telemetry.
     */
    private fun createMetadataInput(
        headers: MultiValueMap<String, String>,
        modelName: String,
    ): CreateResponseMetadataInput = CreateResponseMetadataInput("openai", getApiBaseUri(headers, modelName).host)
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
