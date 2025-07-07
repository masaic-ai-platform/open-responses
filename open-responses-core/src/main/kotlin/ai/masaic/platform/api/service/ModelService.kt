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

//    suspend fun fetchCompletionStream(
//        request: CreateCompletionRequest,
//        apiKey: String,
//    ): Flow<ServerSentEvent<String>> {
//        // build the headers exactly as you did before
//        val authHeaders =
//            MultiValueMap.fromMultiValue(
//                mapOf("Authorization" to listOf(apiKey)),
//            )
//        // any other query params (empty here)
//        val otherParams = MultiValueMap.fromMultiValue(emptyMap<String, List<String>>())
//
//        // delegate directly to your controller
//        val response =
//            completionController.createCompletion(
//                request,
//                authHeaders,
//                otherParams,
//                exchange
//            ) as ResponseEntity<Flow<ServerSentEvent<String>>>
//
//        return response.body as Flow<ServerSentEvent<String>>
//    }
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
