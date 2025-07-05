package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.extensions.isImageContent
import ai.masaic.openresponses.tool.AgenticSearchResponse
import ai.masaic.openresponses.tool.FileSearchResponse
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.completions.CompletionUsage
import com.openai.models.responses.*
import java.time.Instant
import java.util.*

/**
 * Utility class to convert OpenAI's ChatCompletion objects to Masaic API Response objects.
 * This converter enables seamless integration between different API formats and ensures
 * compatibility across the platform.
 */
object ChatCompletionConverter {
    val objectMapper = jacksonObjectMapper().registerModule(Jdk8Module())
    val log = org.slf4j.LoggerFactory.getLogger(ChatCompletionConverter::class.java)

    /**
     * Builds the complete Response object from all components.
     *
     * @param params The ResponseCreateParams
     * @return A fully configured Response object
     */
    fun buildIntermediateResponse(
        params: ResponseCreateParams,
        status: ResponseStatus,
        id: String,
    ): Response =
        Response
            .builder()
            .id(id)
            .createdAt(Instant.now().toEpochMilli().toDouble())
            .error(null) // Required field, null since we assume no error
            .incompleteDetails(null) // Required field, null since we assume complete response
            .instructions(params.instructions())
            .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
            .model(params.model())
            .object_(JsonValue.from("response")) // Standard value
            .temperature(params.temperature())
            .parallelToolCalls(params._parallelToolCalls())
            .tools(params._tools())
            .toolChoice(convertToolChoice(params.toolChoice()))
            .topP(params.topP())
            .maxOutputTokens(params.maxOutputTokens())
            .previousResponseId(params.previousResponseId())
            .reasoning(params.reasoning())
            .status(status)
            .output(listOf())
            .build()

    /**
     * Builds the complete Response object from all components.
     *
     * @param params The ResponseCreateParams
     * @return A fully configured Response object
     */
    fun buildFinalResponse(
        params: ResponseCreateParams,
        status: ResponseStatus,
        id: String,
        outputItems: List<ResponseOutputItem>,
        incompleteDetails: Response.IncompleteDetails? = null,
    ): Response {
        if (outputItems.isNotEmpty() &&
            outputItems.last().isMessage() &&
            params.input().isResponse() &&
            params
                .input()
                .asResponse()
                .last()
                .isFunctionCallOutput() &&
            outputItems.last().isMessage() &&
            outputItems
                .last()
                .asMessage()
                .content()
                .isNotEmpty() &&
            outputItems
                .last()
                .asMessage()
                .content()
                .last()
                .isOutputText()
        ) {
            val functionCallOutput =
                params
                    .input()
                    .asResponse()
                    .last()
                    .asFunctionCallOutput()
            val callId = functionCallOutput.callId()
            val fileSearchFunctionCall =
                params.input().asResponse().filter {
                    it.isFunctionCall() &&
                        it.asFunctionCall().name() == "file_search" &&
                        it.asFunctionCall().callId() == callId
                }

            val agenticSearchFunctionCall =
                params.input().asResponse().filter {
                    it.isFunctionCall() &&
                        it.asFunctionCall().name() == "agentic_search" &&
                        it.asFunctionCall().callId() == callId
                }

            if (fileSearchFunctionCall.isNotEmpty()) {
                try {
                    val response =
                        objectMapper.readValue(
                            functionCallOutput.output().toString(),
                            FileSearchResponse::class.java,
                        )

                    val annotations =
                        response.data.flatMap { it.annotations }.map {
                            ResponseOutputText.Annotation.ofFileCitation(
                                ResponseOutputText.Annotation.FileCitation
                                    .builder()
                                    .type(JsonValue.from(it.type))
                                    .index(JsonValue.from(it.index))
                                    .fileId(it.file_id)
                                    .putAdditionalProperty("filename", JsonValue.from(it.filename))
                                    .build(),
                            )
                        }

                    val list = outputItems.toMutableList()
                    val last = list.removeLast()
                    val isImage =
                        isImageContent(
                            last
                                .asMessage()
                                .content()
                                .last()
                                .asOutputText()
                                .text(),
                        ).isImage
                    list.add(
                        ResponseOutputItem.ofMessage(
                            ResponseOutputMessage
                                .builder()
                                .addContent(
                                    ResponseOutputMessage.Content.ofOutputText(
                                        ResponseOutputText
                                            .builder()
                                            .text(
                                                last
                                                    .asMessage()
                                                    .content()
                                                    .last()
                                                    .asOutputText()
                                                    .text(),
                                            ).annotations(annotations)
                                            .putAdditionalProperty("type", JsonValue.from(if (isImage) "output_image" else "output_text"))
                                            .build(),
                                    ),
                                ).id(UUID.randomUUID().toString())
                                .status(ResponseOutputMessage.Status.COMPLETED)
                                .build(),
                        ),
                    )

                    return Response
                        .builder()
                        .id(id)
                        .createdAt(Instant.now().toEpochMilli().toDouble())
                        .error(null) // Required field, null since we assume no error
                        .incompleteDetails(incompleteDetails) // Required field, null since we assume complete response
                        .instructions(params.instructions())
                        .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
                        .model(params.model())
                        .object_(JsonValue.from("response")) // Standard value
                        .temperature(params.temperature())
                        .parallelToolCalls(params._parallelToolCalls())
                        .tools(params._tools())
                        .toolChoice(convertToolChoice(params.toolChoice()))
                        .topP(params.topP())
                        .maxOutputTokens(params.maxOutputTokens())
                        .previousResponseId(params.previousResponseId())
                        .reasoning(params.reasoning())
                        .status(status)
                        .output(list)
                        .build()
                } catch (e: JsonProcessingException) {
                    // did not succeed to parse the function call. Continue without annotation parse
                    log.warn("Failed to parse function call output: ${e.message}")
                }
            } else if (agenticSearchFunctionCall.isNotEmpty()) {
                try {
                    val response =
                        objectMapper.readValue(
                            functionCallOutput.output().toString(),
                            AgenticSearchResponse::class.java,
                        )

                    val annotations =
                        response.data.flatMap { it.annotations }.map {
                            ResponseOutputText.Annotation.ofFileCitation(
                                ResponseOutputText.Annotation.FileCitation
                                    .builder()
                                    .type(JsonValue.from(it.type))
                                    .index(JsonValue.from(it.index))
                                    .fileId(it.file_id)
                                    .putAdditionalProperty("filename", JsonValue.from(it.filename))
                                    .build(),
                            )
                        }

                    val list = outputItems.toMutableList()
                    val last = list.removeLast()
                    val isImage =
                        isImageContent(
                            last
                                .asMessage()
                                .content()
                                .last()
                                .asOutputText()
                                .text(),
                        ).isImage
                    list.add(
                        ResponseOutputItem.ofMessage(
                            ResponseOutputMessage
                                .builder()
                                .addContent(
                                    ResponseOutputMessage.Content.ofOutputText(
                                        ResponseOutputText
                                            .builder()
                                            .text(
                                                last
                                                    .asMessage()
                                                    .content()
                                                    .last()
                                                    .asOutputText()
                                                    .text(),
                                            ).annotations(annotations)
                                            .putAdditionalProperty("type", JsonValue.from(if (isImage) "output_image" else "output_text"))
                                            .build(),
                                    ),
                                ).id(UUID.randomUUID().toString())
                                .status(ResponseOutputMessage.Status.COMPLETED)
                                .build(),
                        ),
                    )

                    return Response
                        .builder()
                        .id(id)
                        .createdAt(Instant.now().toEpochMilli().toDouble())
                        .error(null) // Required field, null since we assume no error
                        .incompleteDetails(incompleteDetails) // Required field, null since we assume complete response
                        .instructions(params.instructions())
                        .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
                        .model(params.model())
                        .object_(JsonValue.from("response")) // Standard value
                        .temperature(params.temperature())
                        .parallelToolCalls(params._parallelToolCalls())
                        .tools(params._tools())
                        .toolChoice(convertToolChoice(params.toolChoice()))
                        .topP(params.topP())
                        .maxOutputTokens(params.maxOutputTokens())
                        .previousResponseId(params.previousResponseId())
                        .reasoning(params.reasoning())
                        .status(status)
                        .output(list)
                        .build()
                } catch (e: JsonProcessingException) {
                    // did not succeed to parse the function call. Continue without annotation parse
                    log.warn("Failed to parse function call output: ${e.message}")
                }
            }
        }

        return Response
            .builder()
            .id(id)
            .createdAt(Instant.now().toEpochMilli().toDouble())
            .error(null) // Required field, null since we assume no error
            .incompleteDetails(incompleteDetails) // Required field, null since we assume complete response
            .instructions(params.instructions())
            .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
            .model(params.model())
            .object_(JsonValue.from("response")) // Standard value
            .temperature(params.temperature())
            .parallelToolCalls(params._parallelToolCalls())
            .tools(params._tools())
            .toolChoice(convertToolChoice(params.toolChoice()))
            .topP(params.topP())
            .maxOutputTokens(params.maxOutputTokens())
            .previousResponseId(params.previousResponseId())
            .reasoning(params.reasoning())
            .status(status)
            .output(outputItems)
            .build()
    }

    /**
     * Converts a ChatCompletion object to a Response object.
     *
     * @param chatCompletion The OpenAI ChatCompletion object to convert
     * @param params The ResponseCreateParams containing configuration for the response
     * @return A fully populated Response object with equivalent data from the ChatCompletion
     */
    fun toResponse(
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
    ): Response {
        // Process all choices and flatten the resulting output items
        val outputItems = processChoices(chatCompletion, params)

        // Convert created timestamp from epoch seconds to double
        val createdAtDouble = chatCompletion.created().toDouble()

        // Build and return the complete response
        return buildResponse(chatCompletion, params, outputItems, createdAtDouble)
    }

    /**
     * Processes each choice from the ChatCompletion and converts them to ResponseOutputItems.
     *
     * @param completion The ChatCompletion to process
     * @return A flattened list of ResponseOutputItems
     */
    private fun processChoices(
        completion: ChatCompletion,
        params: ResponseCreateParams,
    ): List<ResponseOutputItem> =
        completion
            .choices()
            .map { choice ->
                val messageContent = choice.message().content()
                val responseOutputTextBuilder = buildOutputTextWithAnnotations(choice)

                // Extract reasoning if present within <think> tags
                val reasoning = extractReasoning(messageContent)
                val messageWithoutReasoning = removeReasoning(messageContent, reasoning)

                // Build the list of output items for this choice
                val outputs = mutableListOf<ResponseOutputItem>()

                // Add the main message output
                if (messageWithoutReasoning.isNotBlank()) {
                    outputs.add(createMessageOutput(responseOutputTextBuilder, messageWithoutReasoning, params))
                }

                // Add reasoning output if present
                if (reasoning.isNotBlank()) {
                    outputs.add(createReasoningOutput(reasoning))
                }

                // Add tool calls if present
                addToolCallOutputs(choice, outputs, completion = completion)

                // Handle audio content if present
                if (choice.message().audio().isPresent) {
                    // TODO: Add audio to response when implementation is ready
                }

                outputs
            }.flatten()

    /**
     * Builds a ResponseOutputText.Builder with annotations if present.
     *
     * @param choice The ChatCompletion choice to extract annotations from
     * @return A configured ResponseOutputText.Builder
     */
    private fun buildOutputTextWithAnnotations(choice: ChatCompletion.Choice): ResponseOutputText.Builder {
        val builder = ResponseOutputText.builder()

        if (choice.message().annotations().isPresent) {
            builder.annotations(
                choice
                    .message()
                    .annotations()
                    .map { annotationList ->
                        annotationList.map { annotation ->
                            ResponseOutputText.Annotation.ofUrlCitation(
                                ResponseOutputText.Annotation.UrlCitation
                                    .builder()
                                    .url(annotation.urlCitation().url())
                                    .endIndex(annotation.urlCitation().endIndex())
                                    .type(annotation._type())
                                    .startIndex(annotation.urlCitation().startIndex())
                                    .title(annotation.urlCitation().title())
                                    .build(),
                            )
                        }
                    }.get(),
            )
        }

        return builder
    }

    /**
     * Extracts reasoning content from message if enclosed in <think> tags.
     *
     * @param messageContent The Optional<String> content from the message
     * @return The extracted reasoning text or empty string if none
     */
    private fun extractReasoning(messageContent: Optional<String>): String {
        var reasoning = ""
        messageContent.ifPresent {
            if (it.contains("<think>") && it.contains("</think>")) {
                reasoning = it.substringAfter("<think>").substringBefore("</think>").trim()
            }
        }
        return reasoning
    }

    /**
     * Removes reasoning tags from the message content.
     *
     * @param messageContent The Optional<String> content from the message
     * @param reasoning The reasoning text to remove with its tags
     * @return The message without reasoning tags
     */
    private fun removeReasoning(
        messageContent: Optional<String>,
        reasoning: String,
    ): String {
        var messageWithoutReasoning = ""
        messageContent.ifPresent {
            messageWithoutReasoning = it.replace(reasoning, "").trim()
        }
        if (messageWithoutReasoning.contains("<think>") && messageWithoutReasoning.contains("</think>")) {
            messageWithoutReasoning = messageWithoutReasoning.replace("<think>", "").replace("</think>", "").trim()
        }

        return messageWithoutReasoning
    }

    /**
     * Creates a message output item from the choice.
     *
     * @param builder The ResponseOutputText.Builder to use
     * @param messageText The text content for the message
     * @return A ResponseOutputItem containing the message
     */
    private fun createMessageOutput(
        builder: ResponseOutputText.Builder,
        messageText: String,
        params: ResponseCreateParams,
    ): ResponseOutputItem {
        if (params.input().isResponse()) {
            val inputResponse = params.input().asResponse()
            if (inputResponse.last().isFunctionCallOutput()) {
                val functionCallOutput = inputResponse.last().asFunctionCallOutput()
                val callId = functionCallOutput.callId()
                val fileSearchFunctionCall =
                    inputResponse.filter {
                        it.isFunctionCall() &&
                            it.asFunctionCall().name() == "file_search" &&
                            it.asFunctionCall().callId() == callId
                    }

                val agentSearchFunctionCall =
                    inputResponse.filter {
                        it.isFunctionCall() &&
                            it.asFunctionCall().name() == "agentic_search" &&
                            it.asFunctionCall().callId() == callId
                    }

                if (fileSearchFunctionCall.isNotEmpty()) {
                    try {
                        val response =
                            objectMapper.readValue(
                                functionCallOutput.output().toString(),
                                FileSearchResponse::class.java,
                            )

                        val annotations =
                            response.data.flatMap { it.annotations }.map {
                                ResponseOutputText.Annotation.ofFileCitation(
                                    ResponseOutputText.Annotation.FileCitation
                                        .builder()
                                        .type(JsonValue.from(it.type))
                                        .index(JsonValue.from(it.index))
                                        .fileId(it.file_id)
                                        .putAdditionalProperty("filename", JsonValue.from(it.filename))
                                        .build(),
                                )
                            }
                        val isImage = isImageContent(messageText).isImage
                        return ResponseOutputItem.ofMessage(
                            ResponseOutputMessage
                                .builder()
                                .addContent(
                                    builder
                                        .text(messageText)
                                        .annotations(annotations)
                                        .putAdditionalProperty("type", JsonValue.from(if (isImage) "output_image" else "text"))
                                        .build(),
                                ).id(UUID.randomUUID().toString())
                                .status(ResponseOutputMessage.Status.COMPLETED)
                                .build(),
                        )
                    } catch (e: JsonProcessingException) {
                        // did not succeed to parse the function call. Continue without annotation parse
                        log.warn("Failed to parse function call output: ${e.message}")
                    }
                } else if (agentSearchFunctionCall.isNotEmpty()) {
                    try {
                        val response =
                            objectMapper.readValue(
                                functionCallOutput.output().toString(),
                                AgenticSearchResponse::class.java,
                            )

                        val isImage = isImageContent(messageText).isImage
                        val annotations =
                            response.data.flatMap { it.annotations }.map {
                                ResponseOutputText.Annotation.ofFileCitation(
                                    ResponseOutputText.Annotation.FileCitation
                                        .builder()
                                        .type(JsonValue.from(it.type))
                                        .index(JsonValue.from(it.index))
                                        .fileId(it.file_id)
                                        .putAdditionalProperty("filename", JsonValue.from(it.filename))
                                        .build(),
                                )
                            }

                        return ResponseOutputItem.ofMessage(
                            ResponseOutputMessage
                                .builder()
                                .addContent(
                                    builder
                                        .text(messageText)
                                        .annotations(annotations)
                                        .putAdditionalProperty("type", JsonValue.from(if (isImage) "output_image" else "text"))
                                        .build(),
                                ).id(UUID.randomUUID().toString())
                                .status(ResponseOutputMessage.Status.COMPLETED)
                                .build(),
                        )
                    } catch (e: JsonProcessingException) {
                        // did not succeed to parse the function call. Continue without annotation parse
                        log.warn("Failed to parse function call output: ${e.message}")
                    }
                }
            }
        }

        return ResponseOutputItem.ofMessage(
            ResponseOutputMessage
                .builder()
                .addContent(
                    builder.text(messageText).annotations(listOf()).build(),
                ).id(UUID.randomUUID().toString())
                .status(ResponseOutputMessage.Status.COMPLETED)
                .build(),
        )
    }

    /**
     * Creates a reasoning output item from the choice.
     *
     * @param reasoning The reasoning text
     * @return A ResponseOutputItem containing the reasoning
     */
    private fun createReasoningOutput(
        reasoning: String,
    ): ResponseOutputItem =
        ResponseOutputItem.ofReasoning(
            ResponseReasoningItem
                .builder()
                .addSummary(
                    ResponseReasoningItem.Summary
                        .builder()
                        .text(reasoning)
                        .build(),
                ).id(UUID.randomUUID().toString())
                .build(),
        )

    /**
     * Adds tool call outputs to the outputs list if present in the choice.
     *
     * @param choice The ChatCompletion choice
     * @param outputs The mutable list of outputs to add to
     */
    private fun addToolCallOutputs(
        choice: ChatCompletion.Choice,
        outputs: MutableList<ResponseOutputItem>,
        completion: ChatCompletion,
    ) {
        if (choice.message().toolCalls().isPresent &&
            choice
                .message()
                .toolCalls()
                .get()
                .isNotEmpty()
        ) {
            val toolCalls = choice.message().toolCalls().get()
            toolCalls.forEach { toolCall ->
                outputs.add(
                    ResponseOutputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .id(UUID.randomUUID().toString())
                            .callId(toolCall.id())
                            .name(toolCall.function().name())
                            .arguments(toolCall.function().arguments())
                            .type(JsonValue.from("function_call"))
                            .status(ResponseFunctionToolCall.Status.COMPLETED)
                            .build(),
                    ),
                )
            }
        }
    }

    /**
     * Builds the complete Response object from all components.
     *
     * @param chatCompletion The original ChatCompletion
     * @param params The ResponseCreateParams
     * @param outputItems The processed output items
     * @param createdAtDouble The creation timestamp
     * @return A fully configured Response object
     */
    private fun buildResponse(
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        outputItems: List<ResponseOutputItem>,
        createdAtDouble: Double,
    ): Response {
        if (chatCompletion.choices().any { it.finishReason().asString() == "length" }) {
            val builder =
                Response
                    .builder()
                    .id(chatCompletion.id())
                    .createdAt(createdAtDouble)
                    .error(null) // Required field, null since we assume no error
                    .incompleteDetails(
                        Response.IncompleteDetails
                            .builder()
                            .reason(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS)
                            .build(),
                    ) // Required field, null since we assume complete response
                    .instructions(params.instructions())
                    .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
                    .model(convertModel(chatCompletion.model()))
                    .object_(JsonValue.from("response")) // Standard value
                    .output(outputItems)
                    .temperature(params.temperature())
                    .parallelToolCalls(params._parallelToolCalls())
                    .tools(params._tools())
                    .toolChoice(convertToolChoice(params.toolChoice()))
                    .topP(params.topP())
                    .maxOutputTokens(params.maxOutputTokens())
                    .previousResponseId(params.previousResponseId())
                    .reasoning(params.reasoning())
                    .status(ResponseStatus.INCOMPLETE)
                    .usage(chatCompletion.usage().map(this::convertUsage).orElse(null))

            if (chatCompletion.usage().isPresent) {
                builder.usage(convertUsage(chatCompletion.usage().get()))
            }

            log.info("Response completed for id: ${chatCompletion.id()}")
            return builder.build()
        } else if (chatCompletion.choices().any { it.finishReason().asString() == "content_filter" }) {
            val builder =
                Response
                    .builder()
                    .id(chatCompletion.id())
                    .createdAt(createdAtDouble)
                    .error(
                        ResponseError
                            .builder()
                            .message("The message violated our content policy")
                            .code(ResponseError.Code.SERVER_ERROR)
                            .build(),
                    ) // Required field, null since we assume no error
                    .incompleteDetails(
                        Response.IncompleteDetails
                            .builder()
                            .reason(Response.IncompleteDetails.Reason.CONTENT_FILTER)
                            .build(),
                    ) // Required field, null since we assume complete response
                    .instructions(params.instructions())
                    .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
                    .model(convertModel(chatCompletion.model()))
                    .object_(JsonValue.from("response")) // Standard value
                    .output(outputItems)
                    .temperature(params.temperature())
                    .parallelToolCalls(params._parallelToolCalls())
                    .tools(params._tools())
                    .toolChoice(convertToolChoice(params.toolChoice()))
                    .topP(params.topP())
                    .maxOutputTokens(params.maxOutputTokens())
                    .previousResponseId(params.previousResponseId())
                    .reasoning(params.reasoning())
                    .status(ResponseStatus.FAILED)
            if (chatCompletion.usage().isPresent) {
                builder.usage(convertUsage(chatCompletion.usage().get()))
            }

            log.info("Response completed for id: ${chatCompletion.id()}")
            return builder.build()
        }

        val builder =
            Response
                .builder()
                .id(chatCompletion.id())
                .createdAt(createdAtDouble)
                .error(null) // Required field, null since we assume no error
                .incompleteDetails(null) // Required field, null since we assume complete response
                .instructions(params.instructions())
                .metadata(objectMapper.convertValue(params.metadata(), JsonValue::class.java))
                .model(convertModel(chatCompletion.model()))
                .object_(JsonValue.from("response")) // Standard value
                .output(outputItems)
                .temperature(params.temperature())
                .parallelToolCalls(params._parallelToolCalls())
                .tools(params._tools())
                .toolChoice(convertToolChoice(params.toolChoice()))
                .topP(params.topP())
                .maxOutputTokens(params.maxOutputTokens())
                .previousResponseId(params.previousResponseId())
                .reasoning(params.reasoning())
                .status(ResponseStatus.COMPLETED)

        if (chatCompletion.usage().isPresent) {
            builder.usage(convertUsage(chatCompletion.usage().get()))
        }

        log.info("Response completed for id: ${chatCompletion.id()}")
        return builder.build()
    }

    /**
     * Converts a ResponseCreateParams.ToolChoice to a Response.ToolChoice.
     *
     * @param toolChoice The optional tool choice from the parameters
     * @return The converted Response.ToolChoice
     */
    private fun convertToolChoice(toolChoice: Optional<ResponseCreateParams.ToolChoice>): Response.ToolChoice {
        if (toolChoice.isEmpty) {
            return Response.ToolChoice.ofOptions(ToolChoiceOptions.NONE)
        }

        val responseToolChoice = Response.ToolChoice
        val toolChoiceValue = toolChoice.get()

        return when {
            toolChoiceValue.isOptions() -> responseToolChoice.ofOptions(toolChoiceValue.asOptions())
            toolChoiceValue.isFunction() -> responseToolChoice.ofFunction(toolChoiceValue.asFunction())
            toolChoiceValue.isTypes() -> responseToolChoice.ofTypes(toolChoiceValue.asTypes())
            else -> responseToolChoice.ofOptions(ToolChoiceOptions.NONE)
        }
    }

    /**
     * Converts a string model name to ChatModel enum.
     *
     * @param modelString The model name as a string
     * @return The corresponding ChatModel enum value
     */
    private fun convertModel(modelString: String): ChatModel = ChatModel.of(modelString)

    /**
     * Converts OpenAI's CompletionUsage to Masaic's ResponseUsage.
     * Maps token counts and adds default reasoning tokens.
     *
     * @param completionUsage The CompletionUsage from OpenAI
     * @return A ResponseUsage object with equivalent data
     */
    private fun convertUsage(completionUsage: CompletionUsage): ResponseUsage {
        val builder =
            ResponseUsage
                .builder()
                .inputTokens(completionUsage.promptTokens().toLong())
                .outputTokens(completionUsage.completionTokens().toLong())
                .totalTokens(completionUsage.totalTokens().toLong())

        if (completionUsage.promptTokensDetails().isPresent) {
            builder.inputTokensDetails(
                ResponseUsage.InputTokensDetails
                    .builder()
                    .cachedTokens(
                        completionUsage
                            .promptTokensDetails()
                            .get()
                            .cachedTokens()
                            .get(),
                    ).build(),
            )
        } else {
            builder.inputTokensDetails(
                ResponseUsage.InputTokensDetails
                    .builder()
                    .cachedTokens(0)
                    .build(),
            )
        }

        if (completionUsage.completionTokensDetails().isPresent &&
            completionUsage
                .completionTokensDetails()
                .get()
                .reasoningTokens()
                .isPresent
        ) {
            builder.outputTokensDetails(
                ResponseUsage.OutputTokensDetails
                    .builder()
                    .reasoningTokens(
                        completionUsage
                            .completionTokensDetails()
                            .get()
                            .reasoningTokens()
                            .get(),
                    ).build(),
            )
        } else {
            builder.outputTokensDetails(
                ResponseUsage.OutputTokensDetails
                    .builder()
                    .reasoningTokens(0)
                    .build(),
            )
        }
        return builder.build()
    }

    fun reconstructFromChunks(
        chunks: List<ChatCompletionChunk>,
        modelName: String,
    ): ChatCompletion? {
        if (chunks.isEmpty()) {
            log.warn("Cannot reconstruct ChatCompletion from empty chunk list.")
            return null
        }

        var completionId: String? = chunks.firstNotNullOfOrNull { it.id() }
        if (completionId == null) {
            // Fallback if ID is not in the first chunk but maybe in later ones, or generate one if truly missing.
            // For now, let's assume the first chunk should ideally have it or it's consistent.
            completionId = chunks.firstNotNullOfOrNull { it.id() } ?: UUID.randomUUID().toString()
            log.warn("Completion ID not found in the first chunk, picked first available or generated: $completionId")
        }

        val contentBuffers = mutableMapOf<Int, StringBuilder>()
        val toolCallDeltaBuffers = mutableMapOf<Int, MutableList<ChatCompletionChunk.Choice.Delta.ToolCall>>()
        val finishReasons = mutableMapOf<Int, ChatCompletion.Choice.FinishReason>()
        var usageData: CompletionUsage? = null
        var finalCreatedTs: Long? = chunks.firstNotNullOfOrNull { it.created() }

        for (chunk in chunks) {
            // Prefer the first non-null created timestamp
            if (finalCreatedTs == null) finalCreatedTs = chunk.created()
            
            chunk.usage().ifPresent { usageData = it }

            for (choiceChunk in chunk.choices()) {
                val choiceIndex = choiceChunk.index().toInt()

                choiceChunk.delta().content().ifPresent {
                    contentBuffers.getOrPut(choiceIndex) { StringBuilder() }.append(it)
                }

                choiceChunk.delta().toolCalls().ifPresent { tcList ->
                    if (tcList.isNotEmpty()) {
                        toolCallDeltaBuffers.getOrPut(choiceIndex) { mutableListOf() }.addAll(tcList)
                    }
                }
                // Map from ChatCompletionChunk.Choice.FinishReason to ChatCompletion.Choice.FinishReason
                choiceChunk.finishReason().ifPresent {
                    try {
                        finishReasons[choiceIndex] = ChatCompletion.Choice.FinishReason.of(it.value().name)
                    } catch (e: IllegalArgumentException) {
                        log.warn("Unknown finish reason encountered in chunk: ${it.value().name}")
                        // Potentially default to STOP or handle as an error indicator
                        finishReasons[choiceIndex] = ChatCompletion.Choice.FinishReason.STOP // Default or error
                    }
                }
            }
        }

        val assembledChoices = mutableListOf<ChatCompletion.Choice>()
        val allChoiceIndices = (contentBuffers.keys + toolCallDeltaBuffers.keys + finishReasons.keys).toSet()

        if (allChoiceIndices.isEmpty() && chunks.isNotEmpty()) {
            log.warn("No choice data (content, tool calls, or finish reasons) found across all chunks for $completionId. Returning minimal completion.")
            val builder =
                ChatCompletion
                    .builder()
                    .id(completionId)
                    .model(modelName)
                    .created(finalCreatedTs ?: (System.currentTimeMillis() / 1000L))
                    .choices(emptyList()) // No valid choices to add
            if (usageData != null) {
                builder.usage(usageData)
            }

            return builder.build()
        }

        for (index in allChoiceIndices.sorted()) { // Iterate sorted by index for order
            val messageContent = contentBuffers[index]?.toString()
            val deltaToolCalls = toolCallDeltaBuffers[index]
            val finalFinishReason = finishReasons[index] ?: ChatCompletion.Choice.FinishReason.STOP // Default if no specific reason

            val consolidatedToolCalls = mutableListOf<ChatCompletionMessageToolCall>()
            if (!deltaToolCalls.isNullOrEmpty()) {
                // Group tool call parts by their declared index within the tool_calls array of the message
                val toolPartsByMessageIndex = mutableMapOf<Long, MutableList<ChatCompletionChunk.Choice.Delta.ToolCall>>() 
                deltaToolCalls.forEach { tcPart ->
                    tcPart.index().let { tcMessageIdx ->
                        // This is the index within the tool_calls array itself
                        toolPartsByMessageIndex.getOrPut(tcMessageIdx) { mutableListOf() }.add(tcPart)
                    }
                }

                toolPartsByMessageIndex.toSortedMap().values.forEach { parts ->
                    // Process in order of tool call index
                    var toolCallId: String? = null
                    var functionName: String? = null
                    val functionArguments = StringBuilder()
                    // Assuming type is always function for ChatCompletions

                    parts.forEach { part ->
                        part.id().ifPresent { id -> if (toolCallId == null) toolCallId = id }
                        part.function().ifPresent { func ->
                            func.name().ifPresent { name -> if (functionName == null) functionName = name }
                            func.arguments().ifPresent { args -> functionArguments.append(args) }
                        }
                    }

                    if (toolCallId != null && functionName != null) {
                        consolidatedToolCalls.add(
                            ChatCompletionMessageToolCall
                                .builder()
                                .id(toolCallId)
                                .type(JsonValue.from("function")) // Default to function for chat
                                .function(
                                    ChatCompletionMessageToolCall.Function
                                        .builder()
                                        .name(functionName)
                                        .arguments(functionArguments.toString())
                                        .build(),
                                ).build(),
                        )
                    } else {
                        log.warn("Could not fully reconstruct tool call for choice $index due to missing ID or name in parts: $parts")
                    }
                }
            }

            // Build the message for the choice
            val messageBuilder = ChatCompletionMessage.builder().role(JsonValue.from("assistant"))
            var hasDataForMessage = false
            messageContent?.let {
                messageBuilder.content(it)
                hasDataForMessage = true
            }
            if (consolidatedToolCalls.isNotEmpty()) {
                messageBuilder.toolCalls(consolidatedToolCalls)
                hasDataForMessage = true
                // If content is null/empty but we have tool calls, set content to empty string if needed by builder logic of SDK
                if (messageContent.isNullOrEmpty()) {
                    messageBuilder.content("") 
                }
            }
            
            if (!hasDataForMessage && finalFinishReason == ChatCompletion.Choice.FinishReason.STOP && contentBuffers[index]?.isEmpty() == true) {
                // If message is empty, but it's a deliberate stop, some models might send this.
                messageBuilder.content("")
                hasDataForMessage = true
            }

            if (hasDataForMessage || finishReasons.containsKey(index)) { // Only add choice if it has content/tools or an explicit finish reason
                assembledChoices.add(
                    ChatCompletion.Choice
                        .builder()
                        .index(index.toLong())
                        .message(messageBuilder.refusal(null).build())
                        .logprobs(null)
                        .finishReason(finalFinishReason)
                        .build(),
                )
            } else {
                log.warn("Skipping choice at index $index as it has no content, tool calls, or explicit finish reason.")
            }
        }
        
        if (allChoiceIndices.isNotEmpty() && assembledChoices.isEmpty()) {
            log.warn("Processed chunk data for indices ${allChoiceIndices.joinToString()}, but no valid choices could be assembled for $completionId. This may indicate malformed stream data.")
            // Depending on strictness, could return null or a completion with no choices.
            // Returning null to indicate reconstruction failure clearly.
            return null
        }

        val builder =
            ChatCompletion
                .builder()
                .id(completionId)
                .model(modelName)
                .created(finalCreatedTs ?: (System.currentTimeMillis() / 1000L)) // Use current time as fallback for created
                .choices(assembledChoices)
        if (usageData != null) {
            builder.usage(usageData)
        }
        return builder.build()
    }
}

/**
 * Extension function for cleaner conversion syntax.
 * Allows calling toResponse directly on a ChatCompletion object.
 *
 * @param params The ResponseCreateParams containing configuration for the response
 * @return A fully populated Response object
 */
fun ChatCompletion.toResponse(params: ResponseCreateParams): Response = ChatCompletionConverter.toResponse(this, params)
