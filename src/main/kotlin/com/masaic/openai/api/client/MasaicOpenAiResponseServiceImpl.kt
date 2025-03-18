package com.masaic.openai.api.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.core.http.StreamResponse
import com.openai.models.FunctionDefinition
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.*
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

        val completionRequest: ChatCompletionCreateParams.Builder = if (input.isText()) {
            ChatCompletionCreateParams.builder().addMessage(
                ChatCompletionUserMessageParam.builder().content(input.toString()).build()
            )
        } else {
            val inputItems = input.asResponse()
            val completeMessages = ChatCompletionCreateParams.builder()
            inputItems.forEach { it ->
                if (it.isEasyInputMessage() || it.isMessage() || it.isResponseOutputMessage()) {
                    convertInputMessages(it, completeMessages)
                }
                else if(it.isFunctionCall()){
                    val functionCall = it.asFunctionCall()
                    completeMessages.addMessage(ChatCompletionAssistantMessageParam.builder()
                        .addToolCall(
                            ChatCompletionMessageToolCall.builder()
                                .id(functionCall.callId())
                                .function(ChatCompletionMessageToolCall.Function.builder()
                                    .arguments(functionCall.arguments())
                                    .name(functionCall.name())
                                    .build()
                                )
                                .build())
                        .build())
                }
                else if(it.isFunctionCallOutput()){
                    completeMessages.addMessage(ChatCompletionToolMessageParam.builder().content(it.asFunctionCallOutput().output()).toolCallId(
                        it.asFunctionCallOutput().callId()
                    ).build())
                }
            }

            completeMessages
        }

        completionRequest.model(params.model())
        if (params.toolChoice().isPresent) {
            completionRequest.toolChoice(
                if (params.toolChoice().get().isTypes()) {
                    ChatCompletionToolChoiceOption.ofNamedToolChoice(
                        ChatCompletionNamedToolChoice.builder()
                            .type(JsonValue.from(params.toolChoice().get().asTypes().type().asString().lowercase()))
                            .build()
                    )
                } else if (params.toolChoice().get().isFunction()) {
                    ChatCompletionToolChoiceOption.ofNamedToolChoice(
                        ChatCompletionNamedToolChoice.builder()
                            .function(JsonValue.from(params.toolChoice().get().asFunction().name().lowercase()))
                            .function(
                                ChatCompletionNamedToolChoice.Function.builder()
                                    .name(params.toolChoice().get().asFunction().name()).build()
                            ).build()
                    )
                } else if (params.toolChoice().get().isOptions()) {
                    val toolChoiceOptions = params.toolChoice().get().asOptions()
                    if (toolChoiceOptions.asString().lowercase() == "auto") {
                        ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO)
                    } else {
                        ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.NONE)
                    }
                } else {
                    throw IllegalArgumentException("Unsupported tool choice")
                }
            )
        }

        if (params.tools().isPresent && params.tools().get().isNotEmpty()) {
            val tools = params.tools().get().map {
                val responseTool = it
                if (responseTool.isFunction()) {
                    val functionTool = responseTool.asFunction()
                    return@map ChatCompletionTool.builder().function(
                        jacksonObjectMapper().readValue(
                            jacksonObjectMapper().writeValueAsString(functionTool),
                            FunctionDefinition::class.java
                        )
                    ).build()
                }
                else if (responseTool.isWebSearch()){
                    val webSearchTool = responseTool.asWebSearch()
                    return@map ChatCompletionTool.builder().type(JsonValue.from("function")).function(
                        FunctionDefinition.builder().name(webSearchTool.type().asString()).build()
                    ).build()
                }
                else if (responseTool.isFileSearch()){
                    val fileSearchTool = responseTool.asFileSearch()
                    return@map ChatCompletionTool.builder().type(JsonValue.from("function")).function(
                        FunctionDefinition.builder().name(fileSearchTool._type()).build()
                    ).build()
                }
                else if (responseTool.isComputerUsePreview()){
                    val computerUsePreviewTool = responseTool.asComputerUsePreview()
                    return@map ChatCompletionTool.builder().type(JsonValue.from("function")).function(
                        FunctionDefinition.builder().name(computerUsePreviewTool._type()).build()
                    ).build()
                }
                else throw IllegalArgumentException("Unsupported tool")
            }

            completionRequest.tools(tools)
        }

        completionRequest.temperature(params.temperature())
        completionRequest.maxCompletionTokens(params.maxOutputTokens())
        completionRequest.metadata(params.metadata())
        completionRequest.topP(params.topP())
        completionRequest.store(params.store())
        if(params.text().isPresent && params.text().get().format().isPresent)
        {
            val format = params.text().get().format().get()
            if(format.isText()){
                completionRequest.responseFormat(format.asText())
            }
            else if(format.isJsonObject()){
                completionRequest.responseFormat(format.asJsonObject())
            }
            else if(format.isJsonSchema()){
                completionRequest.responseFormat(
                    ResponseFormatJsonSchema.builder()
                    .type(format.asJsonSchema()._type())
                        .jsonSchema(jacksonObjectMapper().readValue(jacksonObjectMapper().writeValueAsString(format.asJsonSchema().schema()),
                            ResponseFormatJsonSchema.JsonSchema::class.java))
                        .build()
                )
            }
        }
        if(params.reasoning().isPresent && params.reasoning().get().effort().isPresent)
        completionRequest.reasoningEffort(ReasoningEffort.of(params.reasoning().get().effort().get().asString()))

        return completionRequest.build()
    }

    private fun convertInputMessages(
        it: ResponseInputItem,
        completeMessages: ChatCompletionCreateParams.Builder
    ) {

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
                        completeMessages.addMessage(
                            ChatCompletionUserMessageParam.builder().content(
                                ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                    prepareUserContent(contentList)
                                )
                            ).build()
                        )
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
                    val easyInputMessage = it.asEasyInputMessage()
                    if(easyInputMessage.content().isTextInput()){
                        completeMessages.addMessage(
                            ChatCompletionSystemMessageParam.builder()
                                .content(
                                    easyInputMessage.content().asTextInput()
                                ).build()
                        )
                    }
                    else if(easyInputMessage.content().isResponseInputMessageContentList())
                    completeMessages.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(
                                easyInputMessage.content().asResponseInputMessageContentList().first().asInputText().text()
                            ).build()
                    )
                }
            }

            "developer" -> {
                if (it.isEasyInputMessage()) {
                    val easyInputMessage = it.asEasyInputMessage()
                    if(easyInputMessage.content().isTextInput()){
                        completeMessages.addMessage(
                            ChatCompletionDeveloperMessageParam.builder()
                                .content(
                                    easyInputMessage.content().asTextInput()
                                ).build()
                        )
                    }
                    else if(easyInputMessage.content().isResponseInputMessageContentList())
                        completeMessages.addMessage(
                            ChatCompletionDeveloperMessageParam.builder()
                                .content(
                                    easyInputMessage.content().asResponseInputMessageContentList().first().asInputText().text()
                                ).build()
                        )
                }
            }

            "tool" -> {
                if(it.isEasyInputMessage()){
                    val easyInputMessage = it.asEasyInputMessage()
                    completeMessages.addMessage(ChatCompletionToolMessageParam.builder().content(easyInputMessage.content().asTextInput()).toolCallId(
                        easyInputMessage._additionalProperties()["tool_call_id"].toString()
                    ).build())
                }
            }
        }
    }
}

private fun prepareUserContent(message: ResponseInputItem.Message): List<ChatCompletionContentPart> =
    prepareUserContent(message.content())

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
                        .detail(
                            ChatCompletionContentPartImage.ImageUrl.Detail.of(
                                inputImage.detail().value().name.lowercase()
                            )
                        )
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