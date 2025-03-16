package com.masaic.openai.api.client

import com.masaic.openai.api.utils.EventUtils
import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseDeleteParams
import com.openai.models.responses.ResponseRetrieveParams
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextDeltaEvent
import com.openai.models.responses.ResponseTextDoneEvent
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.stream.consumeAsFlow
import org.springframework.http.codec.ServerSentEvent

class MasaicOpenAiResponseServiceImpl(
    private val client: OpenAIClient
) : ResponseService {

    override fun withRawResponse(): ResponseService.WithRawResponse {
        TODO("Not yet implemented")
    }

    override fun inputItems(): InputItemService {
        TODO("Not yet implemented")
    }

    override fun create(
        params: ResponseCreateParams,
        requestOptions: RequestOptions
    ): Response {

        val input = params.input()

        if(input.isText()){
            val response = client.chat().completions().create(ChatCompletionCreateParams.builder().addMessage(
                ChatCompletionUserMessageParam.builder().content(input.toString()).build()
            ).model(params.model()).build())

            return response.toResponse(params)
        }
        else {
            TODO()
        }

        return Response.builder().build()
    }

    override fun createStreaming(
        params: ResponseCreateParams,
        requestOptions: RequestOptions
    ): StreamResponse<ResponseStreamEvent> {
        TODO("Not yet implemented")
    }

    override fun retrieve(
        params: ResponseRetrieveParams,
        requestOptions: RequestOptions
    ): Response {
        TODO("Not yet implemented")
    }

    override fun delete(
        params: ResponseDeleteParams,
        requestOptions: RequestOptions
    ) {
        TODO("Not yet implemented")
    }

    fun createCompletionStream(
        params: ResponseCreateParams
    ): Flow<ServerSentEvent<String>> = flow {
        val input = params.input()

        if(input.isText()){
            val response = client.chat().completions().createStreaming(ChatCompletionCreateParams.builder().addMessage(
                ChatCompletionUserMessageParam.builder().content(input.toString()).build()
            ).model(params.model()).additionalBodyProperties(params._additionalBodyProperties()).build())

            response.stream().consumeAsFlow().collect {
                    it.choices().stream().consumeAsFlow().collect {
                        val chunk = it

                        if (chunk.finishReason().isPresent && chunk.finishReason().get().asString() == "stop") {
                            emit(
                                EventUtils.convertEvent(
                                    ResponseStreamEvent.ofOutputTextDone(
                                        ResponseTextDoneEvent.builder().contentIndex(
                                            0
                                        )
                                            .text("")
                                            .outputIndex(0)
                                            .itemId("0").build()
                                    )
                                )
                            )
                        }

                        if (chunk.delta().content().isPresent && chunk.delta().content().get().isNotBlank()) {
                            val delta = chunk.delta().content().get()
                            val index = chunk.index()
                                emit(
                                    EventUtils.convertEvent(
                                        ResponseStreamEvent.ofOutputTextDelta(
                                            ResponseTextDeltaEvent.builder().contentIndex(
                                                index
                                            )
                                                .outputIndex(0)
                                                .itemId(index.toString())
                                                .delta(delta).build()
                                        )
                                    )
                                )

                        }
                    }
            }
        }
        else {
            TODO()
        }
    }
}