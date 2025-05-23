package ai.masaic.openresponses.api.extensions

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseOutputMessage
import com.openai.models.responses.ResponseOutputText

/**
 * Extension function to copy properties from a ResponseCreateParams.Body to a ResponseCreateParams.Builder.
 * This helps simplify the process of creating request parameters from request bodies.
 *
 * @param body The request body to copy properties from
 * @return The builder with properties copied from the body
 */
suspend fun ResponseCreateParams.Builder.fromBody(
    body: ResponseCreateParams.Body,
    responseStore: ResponseStore,
    objectMapper: ObjectMapper,
): ResponseCreateParams.Builder {
    // Set required parameters
    if (body.previousResponseId().isPresent) {
        responseStore.getResponse(body.previousResponseId().get()) ?: throw ResponseNotFoundException("Previous response not found")
        val previousInputItems =
            responseStore
                .getInputItems(body.previousResponseId().get())
                .map {
                    objectMapper.convertValue(it, ResponseInputItem::class.java)
                }.toMutableList()
        val previousResponseOutputItems = responseStore.getOutputItems(body.previousResponseId().get())
        val currentInputItems =
            if (body.input().isResponse()) {
                body.input().asResponse().toMutableList()
            } else {
                mutableListOf(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage
                            .builder()
                            .content(body.input().asText())
                            .role(
                                EasyInputMessage.Role.USER,
                            ).build(),
                    ),
                )
            }

        previousInputItems.addAll(previousResponseOutputItems.map { objectMapper.convertValue(it, ResponseInputItem::class.java) })
        previousInputItems.addAll(currentInputItems)
        input(ResponseCreateParams.Input.ofResponse(removeImageBody(previousInputItems)))
    } else {
        input(body.input())
    }

    // Extract model name from request
    val modelName =
        if (body.model().isChat()) {
            body
                .model()
                .chat()
                .get()
                .toString()
        } else {
            body.model().string().get()
        }

    // If model contains url@model format, update the model name to just the model part
    if (modelName.contains("@") == true) {
        val parts = modelName.split("@", limit = 2)
        if (parts.size == 2) {
            model(parts[1])
        }
    } else {
        model(modelName)
    }

    // Set optional parameters
    instructions(body.instructions())
    reasoning(body.reasoning())
    parallelToolCalls(body.parallelToolCalls())
    maxOutputTokens(body.maxOutputTokens())
    include(body.include())
    metadata(body.metadata())
    store(body.store())
    temperature(body.temperature())
    topP(body.topP())
    truncation(body.truncation())
    previousResponseId(body.previousResponseId())

    // Set additional properties
    additionalBodyProperties(body._additionalProperties())

    // Set optional parameters that use Optional
    body.text().ifPresent { text(it) }
    body.user().ifPresent { user(it) }
    body.toolChoice().ifPresent { toolChoice(it) }
    body.tools().ifPresent { tools(it) }

    return this
}

fun ResponseCreateParams.Builder.removeImageBody(items: List<ResponseInputItem>): List<ResponseInputItem> {
    // Take all function and function output items
    val imageFunctionIds = items.filter { it.isFunctionCall() && it.asFunctionCall().name() == "image_generation" }.map { it.asFunctionCall().callId() }.toSet()

    if (imageFunctionIds.isEmpty()) {
        return items
    }
    val newItems = mutableListOf<ResponseInputItem>()

    items.forEachIndexed { index, it ->
        if (it.isFunctionCallOutput() && imageFunctionIds.contains(it.asFunctionCallOutput().callId())) {
            val builder = it.asFunctionCallOutput().toBuilder()
            builder.output("<image>")
            newItems.add(ResponseInputItem.ofFunctionCallOutput(builder.build()))
        } else if (it.isEasyInputMessage() &&
            it.asEasyInputMessage().content().isResponseInputMessageContentList() &&
            it.asEasyInputMessage().content().asResponseInputMessageContentList().any {
                it.isInputText() && it.asInputText()._additionalProperties()["type"].toString() == "image"
            }
        ) {
            val builder = it.asEasyInputMessage().toBuilder()
            builder.content("<image>")
            newItems.add(ResponseInputItem.ofEasyInputMessage(builder.build()))
        } else if (it.isResponseOutputMessage() &&
            it.asResponseOutputMessage().content().any {
                it
                    ._json()
                    .get()
                    .toString()
                    .contains("output_format=b64_json") ||
                    it
                        ._json()
                        .get()
                        .toString()
                        .contains("type=image")
            }
        ) {
            val builder = it.asResponseOutputMessage().toBuilder()
            builder.content(
                listOf(
                    ResponseOutputMessage.Content.ofOutputText(
                        ResponseOutputText
                            .builder()
                            .text("<image>")
                            .annotations(listOf())
                            .build(),
                    ),
                ),
            )
            newItems.add(ResponseInputItem.ofResponseOutputMessage(builder.build()))
        } else {
            newItems.add(it)
        }
    }

    return newItems
}

fun ChatCompletionMessage.toChatCompletionMessageParam(objectMapper: ObjectMapper): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam
            .builder()
            .role(this._role())
            .content(objectMapper.convertValue(this.content(), ChatCompletionAssistantMessageParam.Content::class.java))
            .toolCalls(this._toolCalls())
            .build(),
    )
