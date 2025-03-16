package com.masaic.openai.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.masaic.openai.api.extensions.fromBody
import com.masaic.openai.api.utils.EventUtils
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.responses.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.stream.consumeAsFlow
import org.springframework.stereotype.Service

@Service
class ResponseService {

    val client = OpenAIOkHttpClient.fromEnv()

    suspend fun createResponse(request: ResponseCreateParams.Body): Response {

        return client.responses().create(ResponseCreateParams.builder().fromBody(request).build())
    }

    fun createStreamingResponse(request: ResponseCreateParams.Body): Flow<String> = flow {
        client.responses().createStreaming(ResponseCreateParams.builder().fromBody(request).build()).stream()
            .consumeAsFlow().collect {
            emit(EventUtils.convertEvent(it))
        }
    }

    fun getResponse(responseId: String): Response {
        return client.responses().retrieve(ResponseRetrieveParams.builder().responseId(responseId).build())
    }

    fun listInputItems(responseId: String, validLimit: Int, validOrder: String, after: String?, before: String?) {
            //TODO
    }
}

class ResponseNotFoundException(message: String) : RuntimeException(message)