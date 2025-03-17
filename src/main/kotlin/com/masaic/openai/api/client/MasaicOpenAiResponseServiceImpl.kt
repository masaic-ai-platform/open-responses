package com.masaic.openai.api.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.Content.ChatCompletionRequestAssistantMessageContentPart
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartRefusal
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.*
import com.openai.services.blocking.ResponseService
import com.openai.services.blocking.responses.InputItemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
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
            val inputItems =
                jacksonObjectMapper().readValue(jacksonObjectMapper().writeValueAsString(input._json().get()),
                    object : TypeReference<List<ResponseInputItem>>() {})
            val completeMessages = ChatCompletionCreateParams.builder()
            inputItems.forEach { it ->
                val role = if (it.isEasyInputMessage()) it.asEasyInputMessage()
                    .role() else if (it.isResponseOutputMessage()) it.asResponseOutputMessage()
                    ._role() else it.asMessage().role()
                when (role.toString().lowercase()) {
                    "user" -> {
                        if (it.isEasyInputMessage()) {
                            val easyInputMessage = it.asEasyInputMessage()
                            if (easyInputMessage.content().isTextInput()) {
                                completeMessages.addMessage(
                                    ChatCompletionUserMessageParam.builder().content(
                                        ChatCompletionUserMessageParam.Content.ofText(
                                            it.asEasyInputMessage().content().asTextInput()
                                        )
                                    ).build()
                                )
                            } else if (easyInputMessage.content().isResponseInputMessageContentList()) {
                                val contentList = easyInputMessage.content().asResponseInputMessageContentList()
                                completeMessages.addMessage(ChatCompletionUserMessageParam.builder().content(
                                    ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                    prepareUserContent(contentList)
                                    )
                                ).build())
                            } else
                                completeMessages.addMessage(
                                    ChatCompletionUserMessageParam.builder().content(
                                        ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                            prepareUserContent(
                                                it.asMessage()
                                            )
                                        )
                                    ).build()
                                )
                        }
                    }
                        "assistant" -> {
                            if (it.isEasyInputMessage()) {
                                completeMessages.addMessage(
                                    ChatCompletionAssistantMessageParam.builder().content(
                                        ChatCompletionAssistantMessageParam.Content.ofText(
                                            it.asEasyInputMessage().content().asTextInput()
                                        )
                                    ).build()
                                )
                            } else if (it.isResponseOutputMessage()) {
                                it.asResponseOutputMessage().content().forEach {
                                    val outputText = it.asOutputText().text()
                                    completeMessages.addMessage(
                                        ChatCompletionAssistantMessageParam.builder().content(
                                            ChatCompletionAssistantMessageParam.Content.ofText(outputText)
                                        ).build()
                                    )
                                }

                            }
                        }
                        "system" -> {
                            if (it.isEasyInputMessage()) {
                                completeMessages.addMessage(
                                    ChatCompletionSystemMessageParam.builder()
                                        .content(
                                            it.asEasyInputMessage().content().asTextInput()
                                        ).build()
                                )
                            }
                        }

                        "developer" -> {
                            completeMessages.addMessage(
                                ChatCompletionDeveloperMessageParam.builder()
                                    .content(
                                        it.asEasyInputMessage().content().asTextInput()
                                    ).build()
                            )
                        }
                    }
                }
                return completeMessages.model(params.model()).build()
            }
        }
    }

private fun prepareUserContent(message: ResponseInputItem.Message): List<ChatCompletionContentPart> = prepareUserContent(message.content())

private fun prepareUserContent(contentList: List<ResponseInputContent>): List<ChatCompletionContentPart> =
    contentList.map {
        if (it.isInputText()) {
            val inputText = it.asInputText()
            return@map ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(
                    inputText.text()
                ).build()
            )
        } else if (it.isInputImage()) {
            val inputImage = it.asInputImage()
            return@map ChatCompletionContentPart.ofImageUrl(
                ChatCompletionContentPartImage.builder().type(JsonValue.from("image_url")).imageUrl(
                    ChatCompletionContentPartImage.ImageUrl.builder()
                        .url(inputImage._imageUrl())
                        .detail(ChatCompletionContentPartImage.ImageUrl.Detail.of(inputImage.detail().value().name.lowercase()))
                        .putAllAdditionalProperties(inputImage._additionalProperties())
                        .build()
                ).build()
            )
        } else if (it.isInputFile()) {
            val inputFile = it.asInputFile()

            return@map ChatCompletionContentPart.ofFile(
                ChatCompletionContentPart.File.builder().type(JsonValue.from("file")).file(
                    ChatCompletionContentPart.File.FileObject.builder()
                        .fileData(inputFile._fileData())
                        .fileId(inputFile._fileId())
                        .fileName(inputFile._filename()).build()
                ).build()
            )
        } else {
            throw IllegalArgumentException("Unsupported input type")
        }
    }