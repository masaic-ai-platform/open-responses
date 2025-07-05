package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.client.MasaicOpenAiResponseServiceImpl
import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.extensions.fromBody
import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import ai.masaic.openresponses.api.model.ResponseInputItemList
import ai.masaic.openresponses.api.utils.EventUtils
import ai.masaic.openresponses.api.utils.PayloadFormatter
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.Headers
import com.openai.core.http.QueryParams
import com.openai.credential.BearerTokenCredential
import com.openai.errors.OpenAIException
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseErrorEvent
import com.openai.models.responses.ResponseStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
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
 * Service for interacting with the OpenAI API to create and manage responses.
 *
 */
@Service
class MasaicResponseService(
    private val openAIResponseService: MasaicOpenAiResponseServiceImpl,
    private val responseStore: ResponseStore,
    private val payloadFormatter: PayloadFormatter,
    private val objectMapper: ObjectMapper,
) {
    companion object {
        const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
        const val MODEL_DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
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

        @JvmStatic
        fun getApiBaseUri(
            modelName: String,
        ): URI {
            // First check if the model contains url@model or provider@model format
            if (modelName.contains("@")) {
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
                            getDefaultApiUri()
                        }
                    }
                }
            }
            return getDefaultApiUri()
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

        @JvmStatic
        fun getDefaultApiUri(): URI = URI(System.getenv(OPENAI_BASE_URL) ?: MODEL_DEFAULT_BASE_URL)
    }

    @Value("\${api.request.timeout:$DEFAULT_TIMEOUT_SECONDS}")
    private val requestTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS

    /**
     * Creates a response from the OpenAI API with enhanced error handling and timeout.
     *
     * @param request The request body containing parameters for the response
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return The response from the OpenAI API
     * @throws ResponseTimeoutException If the request exceeds the configured timeout
     * @throws ResponseProcessingException If there is an error processing the response
     */
    suspend fun createResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): Response {
        logger.info { "Creating response with model: ${request.model()}" }

        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers, request)

        return try {
            val timeoutMillis = Duration.ofSeconds(requestTimeoutSeconds).toMillis()
            withTimeout(timeoutMillis) {
                openAIResponseService.create(
                    client,
                    createRequestParams(
                        request,
                        headerBuilder,
                        queryBuilder,
                    ),
                    instrumentationMetadataInput(headers, request),
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error { "Request timed out after $requestTimeoutSeconds seconds" }
            throw ResponseTimeoutException("Request timed out after $requestTimeoutSeconds seconds")
        } catch (e: TimeoutException) {
            logger.error { "Request timed out after $requestTimeoutSeconds seconds" }
            throw ResponseTimeoutException("Request timed out after $requestTimeoutSeconds seconds")
        } catch (e: CancellationException) {
            logger.warn { "Request was cancelled" }
            throw e // Let cancellation exceptions propagate
        } catch (e: OpenAIException) {
            logger.error { "Error creating response" }
            throw e
        } catch (e: Exception) {
            logger.error { "Error creating response" }
            throw ResponseProcessingException("Error processing response: ${e.message}")
        }
    }

    /**
     * Creates a streaming response from the OpenAI API with enhanced error handling.
     *
     * @param request The request body containing parameters for the response
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return A flow of server-sent events containing the streaming response
     */
    suspend fun createStreamingResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>,
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Creating streaming response with model: ${request.model()}" }

        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers, request)

        return try {
            openAIResponseService
                .createCompletionStream(
                    client,
                    createRequestParams(
                        request,
                        headerBuilder,
                        queryBuilder,
                    ),
                    instrumentationMetadataInput(headers, request),
                )
                // Add error handling to the flow
                .catch { error ->
                    logger.error { "Error in streaming response" }

                    val errorEvent =
                        EventUtils.convertEvent(
                            ResponseStreamEvent.ofError(
                                ResponseErrorEvent
                                    .builder()
                                    .message("Error in streaming response: ${error.message}")
                                    .code(
                                        "stream_error",
                                    ).param("")
                                    .sequenceNumber(System.nanoTime())
                                    .build(),
                            ),
                            payloadFormatter,
                            objectMapper,
                        )
                    emit(errorEvent) // Emit error event to the client
                    throw ResponseStreamingException("Error in streaming response: ${error.message}", error)
                }.onCompletion { error ->
                    if (error != null) {
                        logger.error { "Stream completed with error" }
                    } else {
                        logger.info { "Stream completed successfully" }
                    }
                }
        } catch (e: Exception) {
            logger.error { "Failed to create streaming response" }
            throw ResponseStreamingException("Failed to create streaming response: ${e.message}", e)
        }
    }

    /**
     * Retrieves a response by ID from the OpenAI API with improved error handling.
     *
     * @param responseId The ID of the response to retrieve
     * @return The response from the OpenAI API
     * @throws ResponseNotFoundException If the response cannot be found
     * @throws ResponseProcessingException If there is an error processing the response
     */
    suspend fun getResponse(
        responseId: String,
    ): Response =
        try {
            responseStore.getResponse(responseId) ?: throw ResponseNotFoundException("Response not found with ID: $responseId")
        } catch (e: Exception) {
            when (e) {
                is ResponseNotFoundException -> throw ResponseNotFoundException("Response with ID $responseId not found")
                else -> throw ResponseProcessingException("Error retrieving response: ${e.message}")
            }
        }

    /**
     * Lists input items for a response with pagination.
     *
     * @param responseId The ID of the response
     * @param limit Maximum number of items to return
     * @param order Sort order (asc or desc)
     * @param after Item ID to list items after
     * @param before Item ID to list items before
     * @return List of input items
     * @throws ResponseNotFoundException If the response cannot be found
     */
    suspend fun listInputItems(
        responseId: String,
        limit: Int,
        order: String,
        after: String?,
        before: String?,
    ): ResponseInputItemList {
        logger.info { "Listing input items for response ID: $responseId" }

        val validLimit = limit.coerceIn(1, 100)
        val validOrder = if (order in listOf("asc", "desc")) order else "asc"

        // First check if response exists
        responseStore.getResponse(responseId) ?: throw ResponseNotFoundException("Response not found with ID: $responseId")

        // Get input items from the response store
        val inputItems = responseStore.getInputItems(responseId)

        val sortedItems =
            if (validOrder == "asc") {
                inputItems.sortedBy { it.createdAt }
            } else {
                inputItems.sortedByDescending { it.createdAt }
            }

        val fromIndex = after?.let { id -> sortedItems.indexOfFirst { it.id == id } + 1 } ?: 0
        val toIndex = before?.let { id -> sortedItems.indexOfFirst { it.id == id } } ?: sortedItems.size

        val validFromIndex = fromIndex.coerceIn(0, sortedItems.size)
        val validToIndex = toIndex.coerceIn(validFromIndex, sortedItems.size)

        val paginatedItems = sortedItems.subList(validFromIndex, validToIndex).take(validLimit)

        val hasMore = (validToIndex - validFromIndex) > paginatedItems.size
        val firstId = paginatedItems.firstOrNull()?.id
        val lastId = paginatedItems.lastOrNull()?.id

        return ResponseInputItemList(
            data = paginatedItems,
            hasMore = hasMore,
            firstId = firstId,
            lastId = lastId,
        )
    }

    /**
     * Creates a flow of server-sent events from a streaming response.
     *
     * @param response The streaming response from the OpenAI API
     * @return A flow of server-sent events
     */
    private fun streamOpenAiResponse(response: AsyncStreamResponse<ResponseStreamEvent>): Flow<ServerSentEvent<String>> =
        callbackFlow {
            val subscription =
                response.subscribe { completion ->
                    try {
                        val event = EventUtils.convertEvent(completion, payloadFormatter, objectMapper)
                        if (!trySend(event).isSuccess) {
                            logger.warn { "Failed to send streaming event to client" }
                        }
                    } catch (e: Exception) {
                        logger.error { "Error processing streaming event" }
                    }
                }

            launch {
                try {
                    subscription.onCompleteFuture().await()
                    logger.debug { "Streaming response completed successfully" }
                } catch (e: Exception) {
                    logger.error { "Error in streaming response completion" }
                }
            }

            awaitClose {
                try {
                    logger.debug { "Cancelling streaming subscription" }
                    subscription.onCompleteFuture().cancel(false)
                } catch (e: Exception) {
                    logger.warn { "Error cancelling streaming subscription" }
                }
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
     * Creates request parameters from the request body and builders.
     *
     * @param request The request body
     * @param headerBuilder The headers builder
     * @param queryBuilder The query parameters builder
     * @return The request parameters
     */
    private suspend fun createRequestParams(
        request: ResponseCreateParams.Body,
        headerBuilder: Headers.Builder,
        queryBuilder: QueryParams.Builder,
    ): ResponseCreateParams =
        ResponseCreateParams
            .builder()
            .fromBody(request, responseStore, objectMapper)
            .additionalHeaders(headerBuilder.build())
            .additionalQueryParams(queryBuilder.build())
            .build()

    /**
     * Creates an OpenAI client with the appropriate credentials and base URL.
     *
     * @param headers The HTTP headers containing authorization information
     * @return An OpenAI client
     * @throws IllegalArgumentException If the API key is missing
     */
    private fun createClient(
        headers: MultiValueMap<String, String>,
        request: ResponseCreateParams.Body,
    ): OpenAIClient {
        val authHeader =
            headers.getFirst("Authorization") ?: headers.getFirst("authorization")
                ?: throw IllegalArgumentException("api-key is missing.")

        val credential =
            BearerTokenCredential.create {
                authHeader.split(" ").getOrNull(1) ?: throw IllegalArgumentException("api-key is missing.")
            }

        val model =
            if (request.model().isChat()) {
                request
                    .model()
                    .chat()
                    .get()
                    .toString()
            } else {
                request.model().string().get()
            }

        // Extract model name for base URL determination
        return OpenAIOkHttpClient
            .builder()
            .credential(credential)
            .baseUrl(getApiBaseUri(headers, model).toURL().toString())
            .build()
    }

    private fun instrumentationMetadataInput(
        headers: MultiValueMap<String, String>,
        request: ResponseCreateParams.Body,
    ): InstrumentationMetadataInput {
        val modelName =
            if (request.model().isChat()) {
                request
                    .model()
                    .chat()
                    .get()
                    .toString()
            } else {
                request.model().string().get()
            }
        val parts = modelName.split("@", limit = 2)

        var genAiSystem = "UNKNOWN"
        var actualModelName = modelName
        if (parts.size == 2) {
            if (!(parts[0].startsWith("http://") || parts[0].startsWith("https://"))) {
                genAiSystem = parts[0]
            }
            actualModelName = parts[1]
        }

        val url = getApiBaseUri(headers, modelName)
        return InstrumentationMetadataInput(genAiSystem, actualModelName, url.host, url.port.toString())
    }
}

/**
 * Exception thrown when a response cannot be found.
 *
 * @param message The error message
 */
class ResponseNotFoundException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when a request times out.
 *
 * @param message The error message
 */
class ResponseTimeoutException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when there is an error processing a response.
 *
 * @param message The error message
 */
class ResponseProcessingException(
    message: String,
) : RuntimeException(message)

/**
 * Exception thrown when there is an error in a streaming response.
 *
 * @param message The error message
 * @param cause The cause of the exception
 */
class ResponseStreamingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
