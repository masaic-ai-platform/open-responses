package ai.masaic.openresponses.api.client

import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
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
            .metadata(params.metadata())
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
    ): Response =
        Response
            .builder()
            .id(id)
            .createdAt(Instant.now().toEpochMilli().toDouble())
            .error(null) // Required field, null since we assume no error
            .incompleteDetails(incompleteDetails) // Required field, null since we assume complete response
            .instructions(params.instructions())
            .metadata(params.metadata())
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
        val outputItems = processChoices(chatCompletion)

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
    private fun processChoices(completion: ChatCompletion): List<ResponseOutputItem> =
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
                    outputs.add(createMessageOutput(choice, responseOutputTextBuilder, messageWithoutReasoning))
                }

                // Add reasoning output if present
                if (reasoning.isNotBlank()) {
                    outputs.add(createReasoningOutput(choice, reasoning))
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
     * @param choice The ChatCompletion choice
     * @param builder The ResponseOutputText.Builder to use
     * @param messageText The text content for the message
     * @return A ResponseOutputItem containing the message
     */
    private fun createMessageOutput(
        choice: ChatCompletion.Choice,
        builder: ResponseOutputText.Builder,
        messageText: String,
    ): ResponseOutputItem =
        ResponseOutputItem.ofMessage(
            ResponseOutputMessage
                .builder()
                .addContent(
                    builder.text(messageText).annotations(listOf()).build(),
                ).id(choice.index().toString())
                .status(ResponseOutputMessage.Status.COMPLETED)
                .build(),
        )

    /**
     * Creates a reasoning output item from the choice.
     *
     * @param choice The ChatCompletion choice
     * @param reasoning The reasoning text
     * @return A ResponseOutputItem containing the reasoning
     */
    private fun createReasoningOutput(
        choice: ChatCompletion.Choice,
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
                ).id(choice.index().toString())
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
                            .id(completion.id())
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
                    .metadata(params.metadata())
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
                    .metadata(params.metadata())
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
                .metadata(params.metadata())
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
}

/**
 * Extension function for cleaner conversion syntax.
 * Allows calling toResponse directly on a ChatCompletion object.
 *
 * @param params The ResponseCreateParams containing configuration for the response
 * @return A fully populated Response object
 */
fun ChatCompletion.toResponse(params: ResponseCreateParams): Response = ChatCompletionConverter.toResponse(this, params)
