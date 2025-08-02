package ai.masaic.platform.api.service

import ai.masaic.openresponses.api.controller.CompletionController
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.models.chat.completions.ChatCompletion
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class ModelService(
    private val completionController: CompletionController,
) {
    /**
     * Gets the raw completion payload (just the JSON text).
     */
    suspend fun fetchCompletionPayload(
        request: CreateCompletionRequest,
        apiKey: String,
    ): String {
        val response: ResponseEntity<*> =
            completionController.createCompletion(
                request,
                MultiValueMap.fromMultiValue(mapOf("Authorization" to listOf("Bearer $apiKey"))),
                MultiValueMap.fromMultiValue(mapOf("ddd" to listOf(""))),
            )
        val chat = response.body as ChatCompletion
        return chat
            .choices()[0]
            .message()
            .content()
            .get()
    }
}

/**
 * Convenient inline + reified deserializer.
 * Delegates to the service’s fetchCompletionPayload, then
 * maps the resulting JSON into T via Jackson.
 */
suspend inline fun <reified T> ModelService.createCompletion(
    request: CreateCompletionRequest,
    apiKey: String,
): T {
    // 1) fetch the raw JSON
    val payloadJson = fetchCompletionPayload(request, apiKey)

    // 2) deserialize using Jackson’s Kotlin module
    val mapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    return mapper.readValue(payloadJson)
}

// 1) Top‐level entry point
fun messages(block: MessageListBuilder.() -> Unit): List<Map<String, String>> = MessageListBuilder().apply(block).build()

// 2) Builder that collects individual maps
class MessageListBuilder {
    private val items = mutableListOf<Map<String, String>>()

    /**
     * Adds a system message with the role pre-set.
     */
    fun systemMessage(content: String) {
        items +=
            mapOf(
                "role" to "system",
                "content" to content,
            )
    }

    /**
     * Adds a user message with the role pre-set.
     */
    fun userMessage(content: String) {
        items +=
            mapOf(
                "role" to "user",
                "content" to content,
            )
    }

    /**
     * If you ever need a different role…
     */
    fun customMessage(
        role: String,
        content: String,
    ) {
        items +=
            mapOf(
                "role" to role,
                "content" to content,
            )
    }

    fun build(): List<Map<String, String>> = items
}
