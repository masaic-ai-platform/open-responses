package ai.masaic.openresponses.api.client

import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ResponseStore.
 * Stores responses and their input items in memory using concurrent hash maps.
 */
@Component
class InMemoryResponseStore : ResponseStore {
    private val logger = KotlinLogging.logger {}
    
    // Thread-safe maps to store responses and their related input items
    private val responses = ConcurrentHashMap<String, Response>()
    private val inputItemsMap = ConcurrentHashMap<String, List<ResponseInputItem>>()

    override fun storeResponse(
        response: Response,
        inputItems: List<ResponseInputItem>,
    ) {
        val responseId = response.id()
        logger.debug { "Storing response with ID: $responseId" }
        responses[responseId] = response
        inputItemsMap[responseId] = inputItems
    }

    override fun getResponse(responseId: String): Response? {
        logger.debug { "Retrieving response with ID: $responseId" }
        return responses[responseId]
    }

    override fun getInputItems(responseId: String): List<ResponseInputItem> {
        logger.debug { "Retrieving input items for response with ID: $responseId" }
        return inputItemsMap[responseId] ?: emptyList()
    }

    override fun deleteResponse(responseId: String): Boolean {
        logger.debug { "Deleting response with ID: $responseId" }
        val responseRemoved = responses.remove(responseId) != null
        inputItemsMap.remove(responseId)
        return responseRemoved
    }
} 
