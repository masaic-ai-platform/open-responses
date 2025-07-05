package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.tool.ToolRequestContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrNull

/**
 * In-memory implementation of ResponseStore.
 * Stores responses and their input items in memory using fixed-size caches with LRU eviction.
 */
@Component
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryResponseStore(
    private val objectMapper: ObjectMapper,
    @Value("\${open-responses.store.cache-size:10000}") private val cacheSize: Long,
) : ResponseStore {
    private val logger = KotlinLogging.logger {}

    private val responses: Cache<String, Response> =
        Caffeine
            .newBuilder()
            .maximumSize(cacheSize)
            .build()
    
    private val inputItemsMap: Cache<String, List<InputMessageItem>> =
        Caffeine
            .newBuilder()
            .maximumSize(cacheSize)
            .build()
    
    private val outputInputItemsMap: Cache<String, List<InputMessageItem>> =
        Caffeine
            .newBuilder()
            .maximumSize(cacheSize)
            .build()

    override suspend fun storeResponse(
        response: Response,
        inputItems: List<ResponseInputItem>,
        context: ToolRequestContext,
    ) {
        val responseId = response.id()
        logger.debug { "Storing response with ID: $responseId" }

        val inputMessageItems: List<InputMessageItem> =
            inputItems.map {
                objectMapper.convertValue(it, InputMessageItem::class.java)
            }

        val outputMessageItems: List<InputMessageItem> =
            response
                .output()
                .mapNotNull { outputItem ->
                    when {
                        // Handle regular messages
                        outputItem.isMessage() && outputItem.message().orElse(null) != null -> {
                            objectMapper.convertValue(outputItem.message().get(), InputMessageItem::class.java)
                        }
                        // Handle function calls
                        outputItem.isFunctionCall() -> {
                            val functionCall = outputItem.asFunctionCall()
                            InputMessageItem(
                                id = functionCall.id().getOrNull() ?: functionCall.callId(),
                                role = "assistant",
                                type = "function_call",
                                call_id = functionCall.callId(),
                                name = functionCall.name(),
                                arguments = functionCall.arguments(),
                            )
                        }
                        else -> null
                    }
                }

        responses.put(responseId, response)
        
        val existingInputItems = inputItemsMap.getIfPresent(responseId)
        if (existingInputItems != null) {
            inputItemsMap.put(responseId, existingInputItems.plus(inputMessageItems.filter { it !in existingInputItems }))
        } else {
            inputItemsMap.put(responseId, inputMessageItems)
        }

        val existingOutputItems = outputInputItemsMap.getIfPresent(responseId)
        if (existingOutputItems != null) {
            outputInputItemsMap.put(responseId, existingOutputItems.plus(outputMessageItems.filter { it !in existingOutputItems }))
        } else {
            outputInputItemsMap.put(responseId, outputMessageItems)
        }
    }

    override suspend fun getResponse(responseId: String): Response? {
        logger.debug { "Retrieving response with ID: $responseId" }
        return responses.getIfPresent(responseId)
    }

    override suspend fun getInputItems(responseId: String): List<InputMessageItem> {
        logger.debug { "Retrieving input items for response with ID: $responseId" }
        return inputItemsMap.getIfPresent(responseId) ?: emptyList()
    }

    override suspend fun deleteResponse(responseId: String): Boolean {
        logger.debug { "Deleting response with ID: $responseId" }
        val responseExists = responses.getIfPresent(responseId) != null
        if (responseExists) {
            responses.invalidate(responseId)
            inputItemsMap.invalidate(responseId)
            outputInputItemsMap.invalidate(responseId)
        }
        return responseExists
    }

    override suspend fun getOutputItems(responseId: String): List<InputMessageItem> = outputInputItemsMap.getIfPresent(responseId) ?: emptyList()
}
