package ai.masaic.openresponses.api.extensions

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem

/**
 * Extension function to copy properties from a ResponseCreateParams.Body to a ResponseCreateParams.Builder.
 * This helps simplify the process of creating request parameters from request bodies.
 *
 * @param body The request body to copy properties from
 * @return The builder with properties copied from the body
 */
fun ResponseCreateParams.Builder.fromBody(
    body: ResponseCreateParams.Body,
    responseStore: ResponseStore,
    objectMapper: ObjectMapper,
): ResponseCreateParams.Builder {
    // Set required parameters
    if (body.previousResponseId().isPresent) {
        val previousResponse = responseStore.getResponse(body.previousResponseId().get()) ?: throw ResponseNotFoundException("Previous response not found")
        val previousInputItems =
            responseStore
                .getInputItems(body.previousResponseId().get())
                .map {
                    objectMapper.convertValue(it, ResponseInputItem::class.java)
                }.toMutableList()
        val previousResponseOutput = previousResponse.output()
        val currentInputItems = body.input().asResponse().toMutableList()

        previousResponseOutput.forEach {
            if (it.message().isPresent) {
                previousInputItems.add(ResponseInputItem.ofResponseOutputMessage(it.message().get()))
            } else if (it.isFunctionCall()) {
                previousInputItems.add(ResponseInputItem.ofFunctionCall(it.asFunctionCall()))
            } else if (it.isReasoning()) {
                previousInputItems.add(ResponseInputItem.ofReasoning(it.asReasoning()))
            } else if (it.isComputerCall()) {
                previousInputItems.add(ResponseInputItem.ofComputerCall(it.asComputerCall()))
            } else if (it.isWebSearchCall()) {
                previousInputItems.add(ResponseInputItem.ofWebSearchCall(it.asWebSearchCall()))
            } else if (it.isFileSearchCall()) {
                previousInputItems.add(ResponseInputItem.ofFileSearchCall(it.asFileSearchCall()))
            }
        }

        previousInputItems.addAll(currentInputItems)
        input(ResponseCreateParams.Input.ofResponse(previousInputItems))
    } else {
        input(body.input())
    }

    // set model
    model(body.model())

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

    // Set additional properties
    additionalBodyProperties(body._additionalProperties())

    // Set optional parameters that use Optional
    body.text().ifPresent { text(it) }
    body.user().ifPresent { user(it) }
    body.toolChoice().ifPresent { toolChoice(it) }
    body.tools().ifPresent { tools(it) }

    return this
}
