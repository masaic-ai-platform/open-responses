package ai.masaic.improved

import ai.masaic.openresponses.api.controller.CompletionController
import ai.masaic.openresponses.api.controller.ResponseController
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.CreateResponseRequest
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.responses.Response
import kotlinx.coroutines.flow.Flow
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import kotlin.jvm.optionals.getOrNull

@Component
class ModelService(
    private val completionController: CompletionController,
    private val responseController: ResponseController,
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
                MultiValueMap.fromMultiValue(mapOf("Authorization" to listOf(apiKey))),
                MultiValueMap.fromMultiValue(mapOf("ddd" to listOf(""))),
            )
        val chat = response.body as ChatCompletion
        return chat
            .choices()[0]
            .message()
            .content()
            .get()
    }

    suspend fun fetchCompletionStream(
        request: CreateCompletionRequest,
        apiKey: String,
    ): Flow<ServerSentEvent<String>> {
        // build the headers exactly as you did before
        val authHeaders =
            MultiValueMap.fromMultiValue(
                mapOf("Authorization" to listOf(apiKey)),
            )
        // any other query params (empty here)
        val otherParams = MultiValueMap.fromMultiValue(emptyMap<String, List<String>>())

        // delegate directly to your controller
        val response =
            completionController.createCompletion(
                request,
                authHeaders,
                otherParams,
            ) as ResponseEntity<Flow<ServerSentEvent<String>>>

        return response.body as Flow<ServerSentEvent<String>>
    }

    /**
     * Gets the raw response payload (just the JSON text) for non-streaming responses.
     */
    suspend fun fetchResponsePayload(
        request: CreateResponseRequest,
        apiKey: String,
        queryParams: Map<String, List<String>> = emptyMap(),
    ): String {
        val authHeaders = MultiValueMap.fromMultiValue(mapOf("Authorization" to listOf(apiKey)))
        val params = MultiValueMap.fromMultiValue(queryParams)
        
        val response: ResponseEntity<*> = responseController.createResponse(
            request,
            authHeaders,
            params,
        )
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        
        val responseObj = mapper.convertValue(response.body, Response::class.java)
        return responseObj
                    .output()
                    .first()
                    .asMessage()
                    .content().first().outputText().getOrNull()?.text() ?: ""
                    .also {
                        if (it.isEmpty()) {
                            throw RuntimeException("No response text found")
        }
                    }
    }

    /**
     * Gets a streaming response flow.
     */
    suspend fun fetchResponseStream(
        request: CreateResponseRequest,
        apiKey: String,
        queryParams: Map<String, List<String>> = emptyMap(),
    ): Flow<ServerSentEvent<String>> {
        // Ensure streaming is enabled
        val streamingRequest = request.copy(stream = true)
        
        val authHeaders = MultiValueMap.fromMultiValue(mapOf("Authorization" to listOf(apiKey)))
        val params = MultiValueMap.fromMultiValue(queryParams)

        val response = responseController.createResponse(
            streamingRequest,
            authHeaders,
            params,
        ) as ResponseEntity<Flow<ServerSentEvent<String>>>

        return response.body as Flow<ServerSentEvent<String>>
    }
}

/**
 * Convenient inline + reified deserializer.
 * Delegates to the service's fetchCompletionPayload, then
 * maps the resulting JSON into T via Jackson.
 */
suspend inline fun <reified T> ModelService.createCompletion(
    request: CreateCompletionRequest,
    apiKey: String,
): T {
    // 1) fetch the raw JSON
    val payloadJson = fetchCompletionPayload(request, apiKey)

    // 2) deserialize using Jackson's Kotlin module
    val mapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    return mapper.readValue(payloadJson)
}

/**
 * Convenient inline + reified deserializer for responses.
 * Delegates to the service's fetchResponsePayload, then
 * maps the resulting JSON into T via Jackson.
 */
suspend inline fun <reified T> ModelService.createResponse(
    request: CreateResponseRequest,
    apiKey: String,
    queryParams: Map<String, List<String>> = emptyMap(),
): T {
    // 1) fetch the raw JSON
    val payloadJson = fetchResponsePayload(request, apiKey, queryParams)

    // 2) deserialize using Jackson's Kotlin module
    val mapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    return mapper.readValue(payloadJson)
}
