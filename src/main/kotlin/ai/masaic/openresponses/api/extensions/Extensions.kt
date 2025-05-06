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
        input(ResponseCreateParams.Input.ofResponse(previousInputItems))
    } else {
        input(body.input())
    }

    // Extract model name from request
    val modelName = body.model().toString()

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

fun ChatCompletionMessage.toChatCompletionMessageParam(objectMapper: ObjectMapper): ChatCompletionMessageParam =
    ChatCompletionMessageParam.ofAssistant(
        ChatCompletionAssistantMessageParam
            .builder()
            .role(this._role())
            .content(objectMapper.convertValue(this.content(), ChatCompletionAssistantMessageParam.Content::class.java))
            .toolCalls(this._toolCalls())
            .build(),
    )
