package com.masaic.openai.api.service

import com.masaic.openai.api.client.MasaicOpenAiResponseServiceImpl
import com.masaic.openai.api.extensions.fromBody
import com.masaic.openai.api.utils.EventUtils
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.Headers
import com.openai.core.http.QueryParams
import com.openai.credential.BearerTokenCredential
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseRetrieveParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.stream.consumeAsFlow
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class MasaicResponseService {

    suspend fun createResponse(
        request: ResponseCreateParams.Body,
        headers: MultiValueMap<String, String>,
        queryParams: MultiValueMap<String, String>
    ): Response {

        val clientBuilder = OpenAIOkHttpClient.builder()

        val headerBuilder: Headers.Builder = Headers.builder()
        headers.filter { it.key == "Authorization" }.forEach { (key, value) -> headerBuilder.put(key,value) }

        val queryBuilder: QueryParams.Builder = QueryParams.builder()

        queryParams.forEach { (key, value) -> queryBuilder.put(key,value) }

        if(headers.containsKey("X-Base-URL")){
            clientBuilder.baseUrl(headers.getFirst("X-Base-URL").toString())
            clientBuilder.credential(BearerTokenCredential.create(headers.getFirst("Authorization")?.toString()?.replace("Bearer", "") ?: throw IllegalArgumentException("api-key is missing.")))

            return MasaicOpenAiResponseServiceImpl(clientBuilder.build()).create(ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                headerBuilder.build()
            ).additionalQueryParams(
                queryBuilder.build()
            )
                .build())
        }
        else {
            clientBuilder.credential(BearerTokenCredential.create(headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")))
            return clientBuilder.build().responses().create(ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                headerBuilder.build()
            ).additionalQueryParams(
                queryBuilder.build()
            ).build())
        }

    }

    suspend fun createStreamingResponse(request: ResponseCreateParams.Body,
                                headers: MultiValueMap<String, String>,
                                queryParams: MultiValueMap<String, String>): Flow<String> {
        val clientBuilder = OpenAIOkHttpClient.builder()
        val headerBuilder: Headers.Builder = Headers.builder()
        headers.filter { it.key == "Authorization" }.forEach { (key, value) -> headerBuilder.put(key,value) }

        val queryBuilder: QueryParams.Builder = QueryParams.builder()

        queryParams.forEach { (key, value) -> queryBuilder.put(key,value) }

        return if(headers.containsKey("X-Base-URL")) {
            clientBuilder.credential(BearerTokenCredential.create(headers.getFirst("Authorization")?.toString()?.replace("Bearer", "")?.trim() ?: throw IllegalArgumentException("api-key is missing.")))
            clientBuilder.baseUrl(headers.getFirst("X-Base-URL").toString())
            MasaicOpenAiResponseServiceImpl(clientBuilder.build()).createCompletionStream(
                ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                    headerBuilder.build()
                ).additionalQueryParams(
                    queryBuilder.build()
                ).build()
            )
        } else {
            clientBuilder.credential(BearerTokenCredential.create(headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")))
            clientBuilder.build().responses().createStreaming(
                ResponseCreateParams.builder().fromBody(request).additionalHeaders(
                    headerBuilder.build()
                ).additionalQueryParams(
                    queryBuilder.build()
                ).build()
            ).stream().consumeAsFlow().map {
                EventUtils.convertEvent(it)
            }
        }
    }

    fun getResponse(responseId: String,
                    headers: MultiValueMap<String, String>,
                    queryParams: MultiValueMap<String, String>): Response {
        val clientBuilder = OpenAIOkHttpClient.builder()

        val headerBuilder: Headers.Builder = Headers.builder()
        headers.forEach { (key, value) -> headerBuilder.put(key,value) }

        val client = clientBuilder.apiKey(headers.getFirst("Authorization") ?: throw IllegalArgumentException("api-key is missing.")).build()

        val queryBuilder: QueryParams.Builder = QueryParams.builder()

        queryParams.forEach { (key, value) -> queryBuilder.put(key,value) }

        return client.responses().retrieve(ResponseRetrieveParams.builder().responseId(responseId)
            .additionalHeaders(headerBuilder.build())
            .additionalQueryParams(queryBuilder.build()).build())
    }

    fun listInputItems(responseId: String, validLimit: Int, validOrder: String, after: String?, before: String?) {
            //TODO
    }
}

class ResponseNotFoundException(message: String) : RuntimeException(message)