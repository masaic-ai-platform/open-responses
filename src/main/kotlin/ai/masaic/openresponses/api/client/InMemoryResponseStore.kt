package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ResponseStore.
 * Stores responses and their input items in memory using concurrent hash maps.
 */
@Component
@ConditionalOnProperty(name = ["app.response-store.type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryResponseStore(
    private val objectMapper: ObjectMapper,
) : ResponseStore {
    private val logger = KotlinLogging.logger {}

    // Thread-safe maps to store responses and their related input items
    private val responses = ConcurrentHashMap<String, Response>()
    private val inputItemsMap = ConcurrentHashMap<String, List<InputMessageItem>>()
    private val outputInputItemsMap = ConcurrentHashMap<String, List<InputMessageItem>>()

    override fun storeResponse(
        response: Response,
        inputItems: List<ResponseInputItem>,
    ) {
        val responseId = response.id()
        logger.debug { "Storing response with ID: $responseId" }

        val inputMessageItems: List<InputMessageItem> =
            inputItems.map {
                // Add mapping logic from ResponseInputItem to InputMessageItem if needed
                objectMapper.convertValue(it, InputMessageItem::class.java)
            }

        val outputMessageItems: List<InputMessageItem> =
            response
                .output()
                .mapNotNull {
                    it.message().orElse(null)
                }.map {
                    objectMapper.convertValue(it, InputMessageItem::class.java)
                }

        responses[responseId] = response
        if (inputItemsMap.containsKey(responseId)) {
            inputItemsMap[responseId] = inputItemsMap.getValue(responseId).plus(inputMessageItems)
        } else {
            inputItemsMap[responseId] = inputMessageItems
        }

        if (outputInputItemsMap.containsKey(responseId)) {
            outputInputItemsMap[responseId] = outputInputItemsMap.getValue(responseId).plus(outputMessageItems)
        } else {
            outputInputItemsMap[responseId] = outputMessageItems
        }
    }

    override fun getResponse(responseId: String): Response? {
        logger.debug { "Retrieving response with ID: $responseId" }
        return responses[responseId]
    }

    override fun getInputItems(responseId: String): List<InputMessageItem> {
        logger.debug { "Retrieving input items for response with ID: $responseId" }
        return inputItemsMap[responseId] ?: emptyList()
    }

    override fun deleteResponse(responseId: String): Boolean {
        logger.debug { "Deleting response with ID: $responseId" }
        val responseRemoved = responses.remove(responseId) != null
        inputItemsMap.remove(responseId)
        return responseRemoved
    }

    override fun getOutputItems(responseId: String): List<InputMessageItem> = outputInputItemsMap[responseId] ?: emptyList()
}
