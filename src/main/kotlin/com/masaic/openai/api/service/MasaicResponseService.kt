package com.masaic.openai.api.service

import com.masaic.openai.api.client.MasaicOpenAiResponseServiceImpl
import com.masaic.openai.api.extensions.fromBody
import com.masaic.openai.api.utils.EventUtils
import com.masaic.openai.tool.ToolService
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.AsyncStreamResponse
import com.openai.core.http.Headers
import com.openai.core.http.QueryParams
import com.openai.credential.BearerTokenCredential
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseRetrieveParams
import com.openai.models.responses.ResponseStreamEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

/**
 * Service for interacting with the OpenAI API to create and manage responses.
 *
 * @property toolService Service for managing and executing tools
 */
@Service
class MasaicResponseService(private val toolService: ToolService) {

    private companion object {
        const val MODEL_BASE_URL = "MODEL_BASE_URL"
        const val MODEL_DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
    }

    /**
     * Creates a response from the OpenAI API.
     *
     * @param request The request body containing parameters for the response
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return The response from the OpenAI API
     */
    suspend fun createResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Response {
        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers)

        return MasaicOpenAiResponseServiceImpl(client, toolService).create(
            createRequestParams(
                request,
                headerBuilder,
                queryBuilder
            )
        )
    }

    /**
     * Creates a streaming response from the OpenAI API.
     *
     * @param request The request body containing parameters for the response
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return A flow of server-sent events containing the streaming response
     */
    suspend fun createStreamingResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Flow<ServerSentEvent<String>> {
        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        val client = createClient(headers)

        return MasaicOpenAiResponseServiceImpl(client, toolService).createCompletionStream(
            createRequestParams(
                request,
                headerBuilder,
                queryBuilder
            )
        )
    }

    /**
     * Retrieves a response by ID from the OpenAI API.
     *
     * @param responseId The ID of the response to retrieve
     * @param headers HTTP headers for the request
     * @param queryParams Query parameters for the request
     * @return The response from the OpenAI API
     */
    fun getResponse(
        responseId: String,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Response {
        val headerBuilder = createHeadersBuilder(headers)
        val queryBuilder = createQueryParamsBuilder(queryParams)
        
        val client = OpenAIOkHttpClient.builder().apiKey(
            headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")
        ).build()

        return client.responses().retrieve(
            ResponseRetrieveParams.builder()
                .responseId(responseId)
                .additionalHeaders(headerBuilder.build())
                .additionalQueryParams(queryBuilder.build())
                .build()
        )
    }

    /**
     * Not implemented: Lists input items for a response.
     */
    fun listInputItems(responseId: String, validLimit: Int, validOrder: String, after: String?, before: String?) {
        //TODO
    }

    /**
     * Creates a flow of server-sent events from a streaming response.
     *
     * @param response The streaming response from the OpenAI API
     * @return A flow of server-sent events
     */
    private fun streamOpenAiResponse(response: AsyncStreamResponse<ResponseStreamEvent>): Flow<ServerSentEvent<String>> =
        callbackFlow {
            val subscription = response.subscribe { completion ->
                trySend(EventUtils.convertEvent(completion)).isSuccess
            }

            launch {
                subscription.onCompleteFuture().await()
                close()
            }

            awaitClose {
                subscription.onCompleteFuture().cancel(false)
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
        headers.filter { it.key == "Authorization" }
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
    private fun createRequestParams(
        request: ResponseCreateParams.Body,
        headerBuilder: Headers.Builder,
        queryBuilder: QueryParams.Builder
    ): ResponseCreateParams {
        return ResponseCreateParams.builder()
            .fromBody(request)
            .additionalHeaders(headerBuilder.build())
            .additionalQueryParams(queryBuilder.build())
            .build()
    }

    /**
     * Creates an OpenAI client with the appropriate credentials and base URL.
     *
     * @param headers The HTTP headers containing authorization information
     * @return An OpenAI client
     */
    private fun createClient(headers: MultiValueMap<String, String>): OpenAIClient {
        val authHeader = headers.getFirst("Authorization") 
            ?: throw IllegalArgumentException("api-key is missing.")

        val credential = BearerTokenCredential.create {
                authHeader.split(" ").getOrNull(1) ?: throw IllegalArgumentException("api-key is missing.")
            }

        return OpenAIOkHttpClient.builder()
            .credential(credential)
            .baseUrl(getApiBaseUrl(headers))
            .build()
    }

    /**
     * Checks if a custom API base URL is configured.
     *
     * @return True if a custom API base URL is configured, false otherwise
     */
    private fun isCustomApiBaseUrl(): Boolean = System.getenv(MODEL_BASE_URL) != null

    /**
     * Gets the API base URL to use for requests.
     *
     * @return The API base URL
     */
//    private fun getApiBaseUrl(): String = System.getenv(OPENAI_API_BASE_URL_ENV) ?: DEFAULT_OPENAI_BASE_URL
    private fun getApiBaseUrl(headers: MultiValueMap<String, String>): String {
        return if (headers.getFirst("x-model-provider")?.lowercase() == "claude") {
            "https://api.anthropic.com/v1"
        } else if (headers.getFirst("x-model-provider")?.lowercase() == "openai") {
            "https://api.openai.com/v1"
        } else {
            System.getenv(MODEL_BASE_URL) ?: MODEL_DEFAULT_BASE_URL
        }
    }
}

/**
 * Exception thrown when a response cannot be found.
 *
 * @param message The error message
 */
class ResponseNotFoundException(message: String) : RuntimeException(message)