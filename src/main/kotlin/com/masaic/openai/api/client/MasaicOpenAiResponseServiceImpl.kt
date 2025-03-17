package com.masaic.openai.api.client

import com.masaic.openai.api.utils.EventUtils
import com.openai.client.OpenAIClient
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.*
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.forEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.stream.consumeAsFlow
import org.springframework.http.codec.ServerSentEvent
import reactor.kotlin.core.publisher.toMono

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

        return client.chat().completions().create(prepareCompletion(params)).toResponse(params)
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
    ): Flow<ServerSentEvent<String>> = callbackFlow {
        val response = client.async().chat().completions().createStreaming(prepareCompletion(params))

        val subscription = response.subscribe { completion ->
            completion.choices().forEach { choice ->
                choice.toResponseStreamEvent()?.let { event ->
                    trySend(event).isSuccess
                }
            }
        }

        launch {
            subscription.onCompleteFuture().await()
            close()
        }

        awaitClose {
            subscription.onCompleteFuture().cancel(true)
        }
    }

    private fun prepareCompletion(
        params: ResponseCreateParams
    ): ChatCompletionCreateParams {

        val input = params.input()

        if (input.isText()) {
            return ChatCompletionCreateParams.builder().addMessage(
                ChatCompletionUserMessageParam.builder().content(input.toString()).build()
            ).model(params.model()).additionalBodyProperties(params._additionalBodyProperties()).build()
        } else {
            val inputItems = input.asResponse()
            val completeMessages = ChatCompletionCreateParams.builder()
            inputItems.filter { it.isMessage() }.map { it.asMessage() }.forEach {
                when (it.role().asString()) {
                    "user" -> completeMessages.addUserMessage(it.content().first().asInputText().text())
                    "assistant" -> completeMessages.addMessage(
                        ChatCompletionAssistantMessageParam.builder().content(it.content().first().asInputText().text())
                            .build()
                    )

                    "system" -> completeMessages.addSystemMessage(it.content().first().asInputText().text())
                    "developer" -> completeMessages.addDeveloperMessage(it.content().first().asInputText().text())
                    else -> {}
                }
            }
            return completeMessages.build()
        }}
}