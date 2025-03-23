package com.masaic.openai.api.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.models.FunctionDefinition
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.*
import com.openai.models.responses.*
import com.openai.core.JsonValue
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Handles conversion of response parameters to chat completion parameters.
 * Encapsulates all the logic for transforming Masaic API request parameters
 * into OpenAI's API format.
 */
@Component
class MasaicParameterConverter {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper()

    /**
     * Prepares a chat completion request from response parameters.
     * This is the main function that transforms ResponseCreateParams into ChatCompletionCreateParams.
     *
     * @param params Parameters for creating the response
     * @return ChatCompletionCreateParams object ready to send to OpenAI API
     */
    fun prepareCompletion(params: ResponseCreateParams): ChatCompletionCreateParams {
        logger.debug { "Converting ResponseCreateParams to ChatCompletionCreateParams" }
        val input = params.input()
        
        try {
            val completionRequest = createBaseCompletionRequest(input)

            applyModelAndParameters(completionRequest, params)
            applyToolConfiguration(completionRequest, params)
            applyResponseFormatting(completionRequest, params)
            applyReasoningEffort(completionRequest, params)

            logger.trace { "Completion request created successfully" }
            return completionRequest.build()
        } catch (e: Exception) {
            logger.error(e) { "Error creating completion request: ${e.message}" }
            throw e
        }
    }

    /**
     * Creates the base completion request with messages.
     *
     * @param input The input from response parameters
     * @return Builder for ChatCompletionCreateParams with messages set
     */
    private fun createBaseCompletionRequest(input: ResponseCreateParams.Input): ChatCompletionCreateParams.Builder {
        return if (input.isText()) {
            logger.debug { "Creating text-based request" }
            createTextBasedRequest(input)
        } else if (input.isResponse()) {
            logger.debug { "Creating message-based request" }
            createMessageBasedRequest(input)
        } else {
            val errorMsg = "Unsupported input type: ${input.javaClass.simpleName}"
            logger.error { errorMsg }
            throw IllegalArgumentException(errorMsg)
        }
    }

    /**
     * Creates a completion request for simple text input.
     *
     * @param input The text input
     * @return Builder with a single user message
     */
    private fun createTextBasedRequest(input: ResponseCreateParams.Input): ChatCompletionCreateParams.Builder {
        logger.trace { "Converting text input to user message" }
        return ChatCompletionCreateParams.builder().addMessage(
            ChatCompletionUserMessageParam.builder().content(input.toString()).build()
        )
    }

    /**
     * Creates a completion request for complex message-based input.
     *
     * @param input The input containing multiple messages
     * @return Builder with all messages properly converted and added
     */
    private fun createMessageBasedRequest(input: ResponseCreateParams.Input): ChatCompletionCreateParams.Builder {
        val inputItems = input.asResponse()
        logger.debug { "Processing ${inputItems.size} input messages" }
        val completionBuilder = ChatCompletionCreateParams.builder()

        try {
            inputItems.forEach { item ->
                when {
                    item.isEasyInputMessage() || item.isMessage() || item.isResponseOutputMessage() -> {
                        logger.trace { "Converting message item: ${item.javaClass.simpleName}" }
                        convertInputMessages(item, completionBuilder)
                    }
                    item.isFunctionCall() -> {
                        logger.trace { "Adding function call: ${item.asFunctionCall().name()}" }
                        addFunctionCallMessage(item, completionBuilder)
                    }
                    item.isFunctionCallOutput() -> {
                        logger.trace { "Adding function call output for ID: ${item.asFunctionCallOutput().callId()}" }
                        addFunctionCallOutputMessage(item, completionBuilder)
                    }
                }
            }

            return completionBuilder
        } catch (e: Exception) {
            logger.error(e) { "Error creating message-based request: ${e.message}" }
            throw e
        }
    }

    /**
     * Adds a function call message to the completion request.
     *
     * @param item The function call input item
     * @param completionBuilder The builder to add the message to
     */
    private fun addFunctionCallMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        val functionCall = item.asFunctionCall()
        completionBuilder.addMessage(
            ChatCompletionAssistantMessageParam.builder()
                .addToolCall(
                    ChatCompletionMessageToolCall.builder()
                        .id(functionCall.callId())
                        .function(
                            ChatCompletionMessageToolCall.Function.builder()
                                .arguments(functionCall.arguments())
                                .name(functionCall.name())
                                .build()
                        )
                        .build()
                )
                .build()
        )
    }

    /**
     * Adds a function call output message to the completion request.
     *
     * @param item The function call output input item
     * @param completionBuilder The builder to add the message to
     */
    private fun addFunctionCallOutputMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        val output = item.asFunctionCallOutput()
        completionBuilder.addMessage(
            ChatCompletionToolMessageParam.builder()
                .content(output.output())
                .toolCallId(output.callId())
                .build()
        )
    }

    /**
     * Applies model and basic parameters to the completion request.
     *
     * @param completionBuilder The builder to add parameters to
     * @param params The source parameters
     */
    private fun applyModelAndParameters(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        completionBuilder.model(params.model())
        completionBuilder.temperature(params.temperature())
        completionBuilder.maxCompletionTokens(params.maxOutputTokens())
        // completionBuilder.metadata(params.metadata()) //TODO Unsupported in groq
        completionBuilder.topP(params.topP())
        //completionBuilder.store(params.store()) //TODO Unsupported in groq
    }

    /**
     * Applies tool configuration to the completion request.
     *
     * @param completionBuilder The builder to add tool configuration to
     * @param params The source parameters
     */
    private fun applyToolConfiguration(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        if (params.toolChoice().isPresent) {
            completionBuilder.toolChoice(createToolChoiceOption(params.toolChoice().get()))
        }

        if (params.tools().isPresent && params.tools().get().isNotEmpty()) {
            completionBuilder.tools(convertTools(params.tools().get()))
        }
    }

    /**
     * Creates a tool choice option based on the provided tool choice.
     *
     * @param toolChoice The tool choice from response parameters
     * @return ChatCompletionToolChoiceOption for the completion request
     */
    private fun createToolChoiceOption(toolChoice: ResponseCreateParams.ToolChoice): ChatCompletionToolChoiceOption {
        return when {
            toolChoice.isTypes() -> {
                ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .type(JsonValue.from(toolChoice.asTypes().type().asString().lowercase()))
                        .build()
                )
            }
            toolChoice.isFunction() -> {
                ChatCompletionToolChoiceOption.ofNamedToolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .function(JsonValue.from(toolChoice.asFunction().name().lowercase()))
                        .function(
                            ChatCompletionNamedToolChoice.Function.builder()
                                .name(toolChoice.asFunction().name()).build()
                        ).build()
                )
            }
            toolChoice.isOptions() -> {
                val toolChoiceOptions = toolChoice.asOptions()
                if (toolChoiceOptions.asString().lowercase() == "auto") {
                    ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.AUTO)
                } else {
                    ChatCompletionToolChoiceOption.ofAuto(ChatCompletionToolChoiceOption.Auto.NONE)
                }
            }
            else -> throw IllegalArgumentException("Unsupported tool choice")
        }
    }

    /**
     * Converts response tools to chat completion tools.
     *
     * @param tools The tools from response parameters
     * @return List of ChatCompletionTool for the completion request
     */
    private fun convertTools(tools: List<Tool>): List<ChatCompletionTool> {
        logger.debug { "Converting ${tools.size} tools" }
        val result = mutableListOf<ChatCompletionTool>()
        
        tools.forEach { responseTool ->
            when {
                responseTool.isFunction() -> {
                    val functionTool = responseTool.asFunction()
                    logger.trace { "Converting function tool: ${functionTool.name()}" }
                        
                    result.add(
                        ChatCompletionTool.builder()
                            .type(JsonValue.from("function"))
                            .function(objectMapper.readValue(
                                objectMapper.writeValueAsString(functionTool),
                                FunctionDefinition::class.java
                            ))
                            .build()
                    )
                }
                responseTool.isWebSearch() -> {
                    val webSearchTool = responseTool.asWebSearch()
                    logger.trace { "Converting web search tool" }
                    result.add(
                        ChatCompletionTool.builder()
                            .type(JsonValue.from("function"))
                            .function(
                                FunctionDefinition.builder()
                                    .name(webSearchTool.type().asString())
                                    .build()
                            )
                            .build()
                    )
                }
                responseTool.isFileSearch() -> {
                    val fileSearchTool = responseTool.asFileSearch()
                    logger.trace { "Converting file search tool" }
                    result.add(
                        ChatCompletionTool.builder()
                            .type(JsonValue.from("function"))
                            .function(
                                FunctionDefinition.builder()
                                    .name(fileSearchTool._type())
                                    .build()
                            )
                            .build()
                    )
                }
                responseTool.isComputerUsePreview() -> {
                    val computerUsePreviewTool = responseTool.asComputerUsePreview()
                    logger.trace { "Converting computer use preview tool" }
                    result.add(
                        ChatCompletionTool.builder()
                            .type(JsonValue.from("function"))
                            .function(
                                FunctionDefinition.builder()
                                    .name(computerUsePreviewTool._type())
                                    .build()
                            )
                            .build()
                    )
                }
                else -> {
                    val errorMsg = "Unsupported tool type: ${responseTool::class.java.simpleName}"
                    logger.error { errorMsg }
                    throw IllegalArgumentException(errorMsg)
                }
            }
        }
        return result
    }

    /**
     * Applies response formatting to the completion request.
     *
     * @param completionBuilder The builder to add formatting to
     * @param params The source parameters
     */
    private fun applyResponseFormatting(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        if (params.text().isPresent && params.text().get().format().isPresent) {
            val format = params.text().get().format().get()
            when {
                format.isText() -> {
                    completionBuilder.responseFormat(format.asText())
                }
                format.isJsonObject() -> {
                    completionBuilder.responseFormat(format.asJsonObject())
                }
                format.isJsonSchema() -> {
                    completionBuilder.responseFormat(
                        ResponseFormatJsonSchema.builder()
                            .type(format.asJsonSchema()._type())
                            .jsonSchema(
                                    ResponseFormatJsonSchema.JsonSchema.builder().name(format.asJsonSchema()._name()).schema(
                                        objectMapper.readValue(
                                            objectMapper.writeValueAsString(format.asJsonSchema().schema()),
                                            ResponseFormatJsonSchema.JsonSchema.Schema::class.java
                                        )
                                    ).build()
                            )
                            .build()
                    )
                }
            }
        }
    }

    /**
     * Applies reasoning effort to the completion request if present.
     *
     * @param completionBuilder The builder to add reasoning effort to
     * @param params The source parameters
     */
    private fun applyReasoningEffort(
        completionBuilder: ChatCompletionCreateParams.Builder,
        params: ResponseCreateParams
    ) {
        if (params.reasoning().isPresent && params.reasoning().get().effort().isPresent) {
            completionBuilder.reasoningEffort(
                ReasoningEffort.of(params.reasoning().get().effort().get().asString())
            )
        }
    }

    /**
     * Converts input messages to chat completion messages based on their role.
     *
     * @param item The input item to convert
     * @param completionBuilder The builder to add the message to
     */
    private fun convertInputMessages(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        val role = when {
            item.isEasyInputMessage() -> item.asEasyInputMessage().role()
            item.isResponseOutputMessage() -> item.asResponseOutputMessage()._role()
            else -> item.asMessage().role()
        }

        when (role.toString().lowercase()) {
            "user" -> handleUserMessage(item, completionBuilder)
            "assistant" -> handleAssistantMessage(item, completionBuilder)
            "system" -> handleSystemMessage(item, completionBuilder)
            "developer" -> handleDeveloperMessage(item, completionBuilder)
            "tool" -> handleToolMessage(item, completionBuilder)
        }
    }

    /**
     * Handles conversion of user messages.
     *
     * @param item The user message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleUserMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            when {
                easyInputMessage.content().isTextInput() -> {
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam.builder().content(
                            ChatCompletionUserMessageParam.Content.ofText(
                                easyInputMessage.content().asTextInput()
                            )
                        ).build()
                    )
                }
                easyInputMessage.content().isResponseInputMessageContentList() -> {
                    val contentList = easyInputMessage.content().asResponseInputMessageContentList()
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam.builder().content(
                            ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                prepareUserContent(contentList)
                            )
                        ).build()
                    )
                }
                else -> {
                    completionBuilder.addMessage(
                        ChatCompletionUserMessageParam.builder().content(
                            ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                prepareUserContent(
                                    item.asMessage()
                                )
                            )
                        ).build()
                    )
                }
            }
        }
    }

    /**
     * Handles conversion of assistant messages.
     *
     * @param item The assistant message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleAssistantMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            completionBuilder.addMessage(
                ChatCompletionAssistantMessageParam.builder().content(
                    ChatCompletionAssistantMessageParam.Content.ofText(
                        item.asEasyInputMessage().content().asTextInput()
                    )
                ).build()
            )
        } else if (item.isResponseOutputMessage()) {
            item.asResponseOutputMessage().content().forEach {
                val outputText = it.asOutputText().text()
                completionBuilder.addMessage(
                    ChatCompletionAssistantMessageParam.builder().content(
                        ChatCompletionAssistantMessageParam.Content.ofText(outputText)
                    ).build()
                )
            }
        }
    }

    /**
     * Handles conversion of system messages.
     *
     * @param item The system message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleSystemMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            when {
                easyInputMessage.content().isTextInput() -> {
                    completionBuilder.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(
                                easyInputMessage.content().asTextInput()
                            ).build()
                    )
                }
                easyInputMessage.content().isResponseInputMessageContentList() -> {
                    completionBuilder.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(
                                easyInputMessage.content().asResponseInputMessageContentList()
                                    .first().asInputText().text()
                            ).build()
                    )
                }
            }
        }
    }

    /**
     * Handles conversion of developer messages.
     *
     * @param item The developer message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleDeveloperMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            when {
                easyInputMessage.content().isTextInput() -> {
                    completionBuilder.addMessage(
                        ChatCompletionDeveloperMessageParam.builder()
                            .content(
                                easyInputMessage.content().asTextInput()
                            ).build()
                    )
                }
                easyInputMessage.content().isResponseInputMessageContentList() -> {
                    completionBuilder.addMessage(
                        ChatCompletionDeveloperMessageParam.builder()
                            .content(
                                easyInputMessage.content().asResponseInputMessageContentList()
                                    .first().asInputText().text()
                            ).build()
                    )
                }
            }
        }
    }

    /**
     * Handles conversion of tool messages.
     *
     * @param item The tool message item
     * @param completionBuilder The builder to add the message to
     */
    private fun handleToolMessage(
        item: ResponseInputItem,
        completionBuilder: ChatCompletionCreateParams.Builder
    ) {
        if (item.isEasyInputMessage()) {
            val easyInputMessage = item.asEasyInputMessage()
            completionBuilder.addMessage(
                ChatCompletionToolMessageParam.builder()
                    .content(easyInputMessage.content().asTextInput())
                    .toolCallId(
                        easyInputMessage._additionalProperties()["tool_call_id"].toString()
                    )
                    .build()
            )
        }
    }

    /**
     * Prepares user content from a message.
     *
     * @param message The message to extract content from
     * @return List of ChatCompletionContentPart objects
     */
    private fun prepareUserContent(message: ResponseInputItem.Message): List<ChatCompletionContentPart> =
        prepareUserContent(message.content())

    /**
     * Prepares user content from a list of response input content.
     * Converts various input types (text, image, file) to appropriate ChatCompletionContentPart objects.
     *
     * @param contentList List of response input content
     * @return List of ChatCompletionContentPart objects
     */
    private fun prepareUserContent(contentList: List<ResponseInputContent>): List<ChatCompletionContentPart> =
        contentList.map { content ->
            when {
                content.isInputText() -> {
                    val inputText = content.asInputText()
                    ChatCompletionContentPart.ofText(
                        ChatCompletionContentPartText.builder().text(
                            inputText.text()
                        ).build()
                    )
                }
                content.isInputImage() -> {
                    val inputImage = content.asInputImage()
                    ChatCompletionContentPart.ofImageUrl(
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
                }
                content.isInputFile() -> {
                    val inputFile = content.asInputFile()
                    ChatCompletionContentPart.ofFile(
                        ChatCompletionContentPart.File.builder().type(JsonValue.from("file")).file(
                            ChatCompletionContentPart.File.FileObject.builder()
                                .fileData(inputFile._fileData())
                                .fileId(inputFile._fileId())
                                .fileName(inputFile._filename()).build()
                        ).build()
                    )
                }
                else -> throw IllegalArgumentException("Unsupported input type")
            }
        }
} 