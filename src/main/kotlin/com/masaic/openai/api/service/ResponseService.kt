package com.masaic.openai.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.masaic.openai.api.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class ResponseService {
    private val responses = ConcurrentHashMap<String, ResponseObject>()
    private val inputItems = ConcurrentHashMap<String, MutableList<InputItem>>()

    suspend fun createResponse(request: CreateResponseRequest): ResponseObject {
        val responseId = "resp_" + UUID.randomUUID().toString().replace("-", "")
        
        // Process input and create input items
        val inputItemsList = processInput(responseId, request.input)
        inputItems[responseId] = inputItemsList.toMutableList()
        
        // Create initial response
        val initialResponse = ResponseObject(
            id = responseId,
            status = ResponseStatus.in_progress,
            model = request.model,
            output = emptyList(),
            createdAt = Instant.now().epochSecond
        )
        
        responses[responseId] = initialResponse
        
        // Generate complete response
        delay(1000) // Simulate processing time
        
        // Generate response content
        val messageId = "msg_" + UUID.randomUUID().toString().replace("-", "")
        val outputText = OutputText(text = generateResponseText(request))
        
        val message = Message(
            id = messageId,
            status = ResponseStatus.completed,
            content = listOf(outputText)
        )
        
        val finalResponse = initialResponse.copy(
            status = ResponseStatus.completed,
            output = listOf(message),
            outputText = outputText
        )
        
        responses[responseId] = finalResponse
        return finalResponse
    }

    fun createStreamingResponse(request: CreateResponseRequest): Flow<String> = flow {
        val responseId = "resp_" + UUID.randomUUID().toString().replace("-", "")
        val messageId = "msg_" + UUID.randomUUID().toString().replace("-", "")
        val objectMapper = ObjectMapper()

        // Process input and create input items
        val inputItemsList = processInput(responseId, request.input)
        inputItems[responseId] = inputItemsList.toMutableList()

        // Create initial response
        val initialResponse = ResponseObject(
            id = responseId,
            status = ResponseStatus.in_progress,
            model = request.model,
            output = emptyList(),
            createdAt = Instant.now().epochSecond
        )

        responses[responseId] = initialResponse

        // Emit response.created event
        val createdEvent = mapOf(
            "type" to "response.created",
            "response" to initialResponse
        )
        emit("event: response.created\ndata: ${objectMapper.writeValueAsString(createdEvent)}\n\n")

        // Emit response.in_progress event
        val inProgressEvent = mapOf(
            "type" to "response.in_progress",
            "response" to initialResponse
        )
        emit("event: response.in_progress\ndata: ${objectMapper.writeValueAsString(inProgressEvent)}\n\n")

        // Emit output item added event
        val outputItem = Message(
            type = "message",
            id = messageId,
            status = ResponseStatus.in_progress,
            role = "assistant",
            content = emptyList()
        )
        val outputItemAddedEvent = mapOf(
            "type" to "response.output_item.added",
            "output_index" to 0,
            "item" to outputItem
        )
        emit("event: response.output_item.added\ndata: ${objectMapper.writeValueAsString(outputItemAddedEvent)}\n\n")

        // Emit content part added event
        val contentPart = OutputText(
            type = "output_text",
            text = "",
            annotations = emptyList()
        )
        val contentPartAddedEvent = mapOf(
            "type" to "response.content_part.added",
            "item_id" to messageId,
            "output_index" to 0,
            "content_index" to 0,
            "part" to contentPart
        )
        emit("event: response.content_part.added\ndata: ${objectMapper.writeValueAsString(contentPartAddedEvent)}\n\n")

        // Generate the full text
        val fullText = "This is a simulated streaming response from the OpenAI API using model: ${request.model}."

        // Split into chunks for streaming
        val chunks = fullText.split(" ")
        var accumulatedText = ""

        // Emit delta events for each chunk
        for (chunk in chunks) {
            delay(100) // Simulate delay between chunks

            // Emit delta event
            val deltaEvent = mapOf(
                "type" to "response.output_text.delta",
                "item_id" to messageId,
                "output_index" to 0,
                "content_index" to 0,
                "delta" to if (accumulatedText.isEmpty()) chunk else " $chunk"
            )
            emit("event: response.output_text.delta\ndata: ${objectMapper.writeValueAsString(deltaEvent)}\n\n")

            accumulatedText += if (accumulatedText.isEmpty()) chunk else " $chunk"
        }

        // Emit output text done event
        val outputTextDoneEvent = mapOf(
            "type" to "response.output_text.done",
            "item_id" to messageId,
            "output_index" to 0,
            "content_index" to 0,
            "text" to accumulatedText
        )
        emit("event: response.output_text.done\ndata: ${objectMapper.writeValueAsString(outputTextDoneEvent)}\n\n")

        // Emit content part done event
        val finalContentPart = OutputText(
            type = "output_text",
            text = accumulatedText,
            annotations = emptyList()
        )
        val contentPartDoneEvent = mapOf(
            "type" to "response.content_part.done",
            "item_id" to messageId,
            "output_index" to 0,
            "content_index" to 0,
            "part" to finalContentPart
        )
        emit("event: response.content_part.done\ndata: ${objectMapper.writeValueAsString(contentPartDoneEvent)}\n\n")

        // Emit output item done event
        val finalOutputItem = Message(
            type = "message",
            id = messageId,
            status = ResponseStatus.completed,
            role = "assistant",
            content = listOf(finalContentPart)
        )
        val outputItemDoneEvent = mapOf(
            "type" to "response.output_item.done",
            "output_index" to 0,
            "item" to finalOutputItem
        )
        emit("event: response.output_item.done\ndata: ${objectMapper.writeValueAsString(outputItemDoneEvent)}\n\n")

        // Update the final response
        val finalResponse = initialResponse.copy(
            status = ResponseStatus.completed,
            output = listOf(finalOutputItem),
            usage = UsageInfo(
                inputTokens = 31,
                inputTokensDetails = InputTokensDetails(cachedTokens = 0),
                outputTokens = 8,
                outputTokensDetails = OutputTokensDetails(reasoningTokens = 0),
                totalTokens = 39
            )
        )
        responses[responseId] = finalResponse

        // Emit response completed event
        val completedEvent = mapOf(
            "type" to "response.completed",
            "response" to finalResponse
        )
        emit("event: response.completed\ndata: ${objectMapper.writeValueAsString(completedEvent)}\n\n")
    }


    fun getResponse(responseId: String): ResponseObject {
        return responses[responseId] ?: throw ResponseNotFoundException("Response not found with ID: $responseId")
    }
    
    fun deleteResponse(responseId: String) {
        if (!responses.containsKey(responseId)) {
            throw ResponseNotFoundException("Response not found with ID: $responseId")
        }
        responses.remove(responseId)
        inputItems.remove(responseId)
    }
    
    fun getInputItems(responseId: String, limit: Int, order: String, after: String?, before: String?): InputItemList {
        val items = inputItems[responseId]?.toList() ?: throw ResponseNotFoundException("Response not found with ID: $responseId")
        
        val sortedItems = if (order == "asc") items.sortedBy { it.id } else items.sortedByDescending { it.id }
        
        val filteredItems = when {
            after != null -> sortedItems.dropWhile { it.id != after }.drop(1).take(limit)
            before != null -> {
                val beforeIndex = sortedItems.indexOfFirst { it.id == before }
                if (beforeIndex > 0) {
                    sortedItems.subList(0, beforeIndex).takeLast(limit)
                } else {
                    emptyList()
                }
            }
            else -> sortedItems.take(limit)
        }
        
        return InputItemList(
            data = filteredItems,
            firstId = filteredItems.firstOrNull()?.id ?: "",
            lastId = filteredItems.lastOrNull()?.id ?: "",
            hasMore = filteredItems.size == limit && filteredItems.lastOrNull() != sortedItems.lastOrNull()
        )
    }
    
    private fun processInput(responseId: String, input: Any): List<InputItem> {
        // This is a simplified implementation
        // In a real application, you would process different types of inputs
        val inputId = "input_" + UUID.randomUUID().toString().replace("-", "")
        
        return when (input) {
            is String -> {
                listOf(
                    MessageInputItem(
                        id = inputId,
                        role = "user",
                        status = ResponseStatus.completed,
                        content = listOf(InputText(text = input))
                    )
                )
            }
            is Map<*, *> -> {
                if (input.containsKey("text")) {
                    listOf(
                        MessageInputItem(
                            id = inputId,
                            role = "user",
                            status = ResponseStatus.completed,
                            content = listOf(InputText(text = input["text"].toString()))
                        )
                    )
                } else {
                    listOf(
                        MessageInputItem(
                            id = inputId,
                            role = "user",
                            status = ResponseStatus.completed,
                            content = listOf(InputText(text = "Input: ${input}"))
                        )
                    )
                }
            }
            else -> {
                listOf(
                    MessageInputItem(
                        id = inputId,
                        role = "user",
                        status = ResponseStatus.completed,
                        content = listOf(InputText(text = "Input: ${input}"))
                    )
                )
            }
        }
    }
    
    private fun generateResponseText(request: CreateResponseRequest): String {
        // This is a simplified implementation
        // In a real application, you would call the actual OpenAI API
        return "This is a simulated response from the OpenAI API using model: ${request.model}. " +
                "In a real implementation, this would connect to the actual OpenAI API."
    }
}

class ResponseNotFoundException(message: String) : RuntimeException(message) 