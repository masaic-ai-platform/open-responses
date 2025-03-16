package com.masaic.openai.api.client

import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.completions.CompletionUsage
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseReasoningItem
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseUsage
import com.openai.models.responses.ToolChoiceOptions
import java.util.Optional

/**
 * Utility function to convert a ChatCompletion object to a Response object.
 * This allows for compatibility between different API formats.
 */
object ChatCompletionConverter {

    /**
     * Converts a ChatCompletion object to a Response object.
     *
     * @param chatCompletion The ChatCompletion object to convert
     * @return A Response object with equivalent data
     */
    fun toResponse(chatCompletion: ChatCompletion, params: ResponseCreateParams): Response {
        // Extract the first choice's message content if available
        // Create output items from the choices
        val outputItems = chatCompletion.choices().map { choice ->

            val messageContent = choice.message().content()

            var reasoning = ""

            messageContent.ifPresent {
                reasoning = it.substringAfter("<think>").substringBefore("</think>")
            }
            var messageWithoutReasoning = ""

            messageContent.ifPresent {
                messageWithoutReasoning = it.replace("<think>$reasoning</think>", "")
            }

            val outputs = mutableListOf<ResponseOutputItem>()

            outputs.add(ResponseOutputItem.ofMessage(
                ResponseOutputMessage.builder().addContent(ResponseOutputText.builder()
                    .annotations(emptyList())
                    .text(messageWithoutReasoning)
                    .build()).id(choice.index().toString()).status(ResponseOutputMessage.Status.COMPLETED).build())
            )

            if(reasoning.isNotBlank()) {
                outputs.add(
                    ResponseOutputItem.ofReasoning(
                        ResponseReasoningItem.builder()
                            .addSummary(ResponseReasoningItem.Summary.builder().text(reasoning).build())
                            .id(choice.index().toString())
                            .build()
                    )
                )
            }

            outputs
        }.flatten()

        // Convert created timestamp from epoch seconds to epoch milliseconds
        val createdAtDouble = chatCompletion.created().toDouble()

        // Build the response
        // Build the response with all required fields
        return Response.builder()
            .id(chatCompletion.id())
            .createdAt(createdAtDouble)
            .error(null) // Required field, but null since we assume no error
            .incompleteDetails(null) // Required field, but null since we assume complete response
            .instructions(params.instructions()) // Required field, default to empty string
            .metadata(params.metadata()) // Required field
            .model(convertModel(chatCompletion.model()))
            .object_(JsonValue.from("response")) // Standard value
            .output(outputItems)
            .temperature(params.temperature()) // Required field, default to 0.0
            .parallelToolCalls(params._parallelToolCalls()) // Required field, default to false
            .tools(params._tools()) // Required field, default to empty list
            .toolChoice(convertToolChoice(params.toolChoice()))
            .topP(params.topP()) // Required field, default to 1.0
            .maxOutputTokens(params.maxOutputTokens()) // Optional field
            .previousResponseId(params.previousResponseId()) // Optional field
            .reasoning(params.reasoning()) // Optional field
            .status(ResponseStatus.COMPLETED) // Default to COMPLETE
            // Optional field
            .usage(convertUsage(chatCompletion.usage().get()))
            .build()
    }

    private fun convertToolChoice(toolChoice: Optional<ResponseCreateParams.ToolChoice>): Response.ToolChoice {

        if(toolChoice.isEmpty){
            return Response.ToolChoice.ofOptions(ToolChoiceOptions.NONE)
        }
        val responseToolChoice = Response.ToolChoice

        if(toolChoice.get().isOptions()){
            return responseToolChoice.ofOptions(toolChoice.get().asOptions())
        }

        if(toolChoice.get().isFunction()){
            return responseToolChoice.ofFunction(toolChoice.get().asFunction())
        }

        if(toolChoice.get().isTypes()){
            return responseToolChoice.ofTypes(toolChoice.get().asTypes())
        }

        return responseToolChoice.ofOptions(ToolChoiceOptions.NONE)
    }

    /**
     * Converts a string model name to ChatModel enum
     */
    private fun convertModel(modelString: String): ChatModel {
        return ChatModel.of(modelString)
    }

    /**
     * Converts CompletionUsage to ResponseUsage
     */
    private fun convertUsage(completionUsage: CompletionUsage): ResponseUsage {
        return completionUsage.let {
            ResponseUsage.builder()
                .inputTokens(it.promptTokens().toLong())
                .outputTokens(it.completionTokens().toLong())
                .totalTokens(it.totalTokens().toLong())
                .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder().reasoningTokens(0).build())
                .build()
        }
    }
}

/**
 * Extension function for cleaner conversion syntax
 */
fun ChatCompletion.toResponse(params: ResponseCreateParams): Response {
    return ChatCompletionConverter.toResponse(this, params)
}