package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.tool.CompletionToolRequestContext
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessageParam

interface CompletionStore {
    /**
     * Stores a chat completion.
     *
     * @param completion The chat completion to store
     * @param messages The messages associated with this completion
     * @param context Optional context for the tool request
     * @return The stored chat completion
     */
    suspend fun storeCompletion(
        completion: ChatCompletion,
        messages: List<ChatCompletionMessageParam>,
        context: CompletionToolRequestContext? = null,
    ): ChatCompletion

    /**
     * Retrieves a chat completion by ID.
     *
     * @param completionId The ID of the chat completion to retrieve
     * @return The chat completion or null if not found
     */
    suspend fun getCompletion(completionId: String): ChatCompletion?

    /**
     * Gets the messages for a chat completion.
     *
     * @param completionId The ID of the chat completion
     * @return The list of messages or an empty list if none found
     */
    suspend fun getMessages(completionId: String): List<ChatCompletionMessageParam>

    /**
     * Deletes a chat completion.
     *
     * @param completionId The ID of the chat completion to delete
     * @return True if the completion was deleted, false otherwise
     */
    suspend fun deleteCompletion(completionId: String): Boolean
} 
