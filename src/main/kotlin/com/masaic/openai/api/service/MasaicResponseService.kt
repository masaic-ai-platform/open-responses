package com.masaic.openai.api.service

import com.masaic.openai.api.client.MasaicOpenAiResponseServiceImpl
import com.masaic.openai.api.extensions.fromBody
import com.masaic.openai.api.utils.EventUtils
import com.masaic.openai.tool.ToolService
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

@Service
class MasaicResponseService(private val toolService: ToolService) {

    suspend fun createResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Response {

        val headerBuilder: Headers.Builder = Headers.builder()
        headers.filter { it.key == "Authorization" }.forEach { (key, value) -> headerBuilder.put(key, value) }

        val queryBuilder: QueryParams.Builder = QueryParams.builder()

        queryParams.forEach { (key, value) -> queryBuilder.put(key, value) }

        if (System.getenv("OPENAI_API_BASE_URL") != null ) {

            val client = OpenAIOkHttpClient.builder().credential(
                BearerTokenCredential.create {
                    headers.getFirst("Authorization")?.split(" ")[1] ?: throw IllegalArgumentException("api-key is missing.")
                }
            ).baseUrl(System.getenv("OPENAI_API_BASE_URL")).build()

            return MasaicOpenAiResponseServiceImpl(client, toolService).create(
                ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                    headerBuilder.build()
                ).additionalQueryParams(
                    queryBuilder.build()
                )
                    .build()
            )
        } else {
            val client = OpenAIOkHttpClient.builder().credential(
                BearerTokenCredential.create {
                    headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")
                }
            ).baseUrl("https://api.openai.com/v1").build()
            return client.responses().create(
                ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                    headerBuilder.build()
                ).additionalQueryParams(
                    queryBuilder.build()
                ).build()
            )
        }
    }

    suspend fun createStreamingResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Flow<ServerSentEvent<String>> {
        val headerBuilder: Headers.Builder = Headers.builder()
        headers.filter { it.key == "Authorization" }.forEach { (key, value) -> headerBuilder.put(key, value) }

        val queryBuilder: QueryParams.Builder = QueryParams.builder()

        queryParams.forEach { (key, value) -> queryBuilder.put(key, value) }

        return if (System.getenv("OPENAI_API_BASE_URL") != null) {
            val client = OpenAIOkHttpClient.builder().credential(
                BearerTokenCredential.create {
                    headers.getFirst("Authorization")?.split(" ")[1] ?: throw IllegalArgumentException("api-key is missing.")
                }
            ).baseUrl(System.getenv("OPENAI_API_BASE_URL")).build()

            MasaicOpenAiResponseServiceImpl(client, toolService).createCompletionStream(
                ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                    headerBuilder.build()
                ).additionalQueryParams(
                    queryBuilder.build()
                ).build()
            )
        } else {
            val client = OpenAIOkHttpClient.builder().credential(
                BearerTokenCredential.create {
                    headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")
                }
            ).baseUrl("https://api.openai.com/v1").build()
            return streamOpenAiResponse(
                client.async().responses().createStreaming(
                    ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                        headerBuilder.build()
                    ).additionalQueryParams(
                        queryBuilder.build()
                    ).build()
                )
            )
        }
    }

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

    fun getResponse(
        responseId: String,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Response {
        val clientBuilder = OpenAIOkHttpClient.builder()

        val headerBuilder: Headers.Builder = Headers.builder()
        headers.forEach { (key, value) -> headerBuilder.put(key, value) }

        val client = clientBuilder.apiKey(
            headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")
        ).build()

        val queryBuilder: QueryParams.Builder = QueryParams.builder()

        queryParams.forEach { (key, value) -> queryBuilder.put(key, value) }

        return client.responses().retrieve(
            ResponseRetrieveParams.builder().responseId(responseId)
                .additionalHeaders(headerBuilder.build())
                .additionalQueryParams(queryBuilder.build()).build()
        )
    }

    fun listInputItems(responseId: String, validLimit: Int, validOrder: String, after: String?, before: String?) {
        //TODO
    }
}

class ResponseNotFoundException(message: String) : RuntimeException(message)