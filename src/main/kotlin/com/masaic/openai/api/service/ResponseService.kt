package com.masaic.openai.api.service

import com.masaic.openai.api.model.CreateResponseRequest
import com.masaic.openai.api.utils.EventUtils
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import com.openai.models.responses.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.stream.consumeAsFlow
import org.springframework.stereotype.Service

@Service
class ResponseService {

    val client = OpenAIOkHttpClient.fromEnv()
    val streamingClient = OpenAIOkHttpClientAsync.fromEnv()

    suspend fun createResponse(request: CreateResponseRequest): Response {

        val createParams = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.instructions)
            .input(ResponseCreateParams.Input.ofText(request.input.toString()))
            .build()

        return client.responses().create(createParams)
    }

    fun createStreamingResponse(request: CreateResponseRequest): Flow<String> = flow {

        val createParams = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.instructions)
            .input(ResponseCreateParams.Input.ofText(request.input.toString()))
            .build()

        client.responses().createStreaming(createParams).stream().consumeAsFlow().collect {
            emit(EventUtils.convertEvent(it))
        }
    }

    fun getResponse(responseId: String): Response{
        return client.responses().retrieve(ResponseRetrieveParams.builder().responseId(responseId).build())
    }
}

class ResponseNotFoundException(message: String) : RuntimeException(message) 