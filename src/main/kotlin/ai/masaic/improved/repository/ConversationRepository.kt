package ai.masaic.improved.repository

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.model.ListConversationsParams

/**
 * Repository interface for Conversation operations.
 * 
 * This interface defines the common operations for accessing and manipulating
 * conversation data regardless of the underlying storage implementation.
 */
interface ConversationRepository {
    /**
     * Create a new conversation.
     *
     * @param conversation The conversation to create
     * @return The created conversation
     */
    suspend fun createConversation(conversation: Conversation): Conversation

    /**
     * Get a conversation by ID.
     *
     * @param conversationId The ID of the conversation to retrieve
     * @return The conversation, or null if not found
     */
    suspend fun getConversation(conversationId: String): Conversation?

    /**
     * List all conversations.
     *
     * @return A list of all conversations
     */
    suspend fun listConversations(): List<Conversation>

    /**
     * List conversations with pagination and filtering.
     *
     * @param params The parameters for listing conversations
     * @return A list of conversations that match the criteria
     */
    suspend fun listConversations(params: ListConversationsParams): List<Conversation>

    /**
     * Update a conversation.
     *
     * @param conversation The conversation to update
     * @return The updated conversation, or null if not found
     */
    suspend fun updateConversation(conversation: Conversation): Conversation?

    /**
     * Delete a conversation.
     *
     * @param conversationId The ID of the conversation to delete
     * @return True if the conversation was deleted, false otherwise
     */
    suspend fun deleteConversation(conversationId: String): Boolean
} 