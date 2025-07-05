package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.tool.ToolRequestContext
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem

/**
 * Interface for storing and retrieving Response objects.
 * This enables persistence and retrieval of response data including associated input items.
 * Uses coroutines for non-blocking operations.
 */
interface ResponseStore {
    /**
     * Stores a response along with its input items.
     *
     * @param response The response to store
     * @param inputItems The input items associated with the response
     */
    suspend fun storeResponse(
        response: Response,
        inputItems: List<ResponseInputItem>,
        context: ToolRequestContext,
    )

    /**
     * Retrieves a response by its ID.
     *
     * @param responseId The ID of the response to retrieve
     * @return The response if found, null otherwise
     */
    suspend fun getResponse(responseId: String): Response?

    /**
     * Retrieves input items for a response by its ID.
     *
     * @param responseId The ID of the response whose input items to retrieve
     * @return List of input items if the response exists, empty list otherwise
     */
    suspend fun getInputItems(responseId: String): List<InputMessageItem>

    /**
     * Removes a response and its input items.
     *
     * @param responseId The ID of the response to delete
     * @return true if the response was deleted, false if it didn't exist
     */
    suspend fun deleteResponse(responseId: String): Boolean

    /**
     * Retrieves output items for a response by its ID.
     *
     * @param responseId The ID of the response whose output items to retrieve
     * @return List of output items if the response exists, empty list otherwise
     */
    suspend fun getOutputItems(responseId: String): List<InputMessageItem>
} 
