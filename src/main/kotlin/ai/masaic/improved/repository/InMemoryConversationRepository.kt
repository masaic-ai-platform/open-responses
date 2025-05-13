package ai.masaic.improved.repository

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.model.ListConversationsParams
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ConversationRepository.
 *
 * This implementation stores conversation data in memory using a ConcurrentHashMap.
 * It is intended for development and testing purposes.
 * 
 * It is only enabled when open-responses.store.type=memory
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory")
class InMemoryConversationRepository : ConversationRepository {
    private val logger = KotlinLogging.logger {}
    private val conversations = ConcurrentHashMap<String, Conversation>()

    /**
     * Create a new conversation.
     * 
     * @param conversation The conversation to create
     * @return The created conversation
     */
    override suspend fun createConversation(conversation: Conversation): Conversation {
        try {
            // Generate an id if it's not provided or empty
            val conversationWithId =
                if (conversation.id.isBlank()) {
                    val uuid = java.util.UUID.randomUUID().toString().replace("-", "")
                    conversation.copy(id = "conv_$uuid")
                } else {
                    conversation
                }
            
            conversations[conversationWithId.id] = conversationWithId
            logger.info { "Saved conversation with ID: ${conversationWithId.id}" }
            return conversationWithId
        } catch (e: Exception) {
            logger.error(e) { "Error saving conversation" }
            throw e
        }
    }

    /**
     * Get a conversation by ID.
     *
     * @param conversationId The ID of the conversation to retrieve
     * @return The conversation, or null if not found
     */
    override suspend fun getConversation(conversationId: String): Conversation? =
        try {
            conversations[conversationId].also {
                if (it == null) {
                    logger.info { "Conversation not found with ID: $conversationId" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting conversation with ID: $conversationId" }
            null
        }

    /**
     * List all conversations.
     *
     * @return A list of all conversations
     */
    override suspend fun listConversations(): List<Conversation> =
        try {
            conversations.values.sortedByDescending { it.createdAt }.toList()
        } catch (e: Exception) {
            logger.error(e) { "Error listing conversations" }
            emptyList()
        }

    /**
     * List conversations with pagination and filtering.
     *
     * @param params The parameters for listing conversations
     * @return A list of conversations that match the criteria
     */
    override suspend fun listConversations(params: ListConversationsParams): List<Conversation> {
        try {
            var filteredConversations = conversations.values.toList()
            
            // Apply resolved filter if provided
            if (params.resolved != null) {
                filteredConversations = filteredConversations.filter { it.resolved == params.resolved }
            }
            
            // Apply label filter if provided
            if (params.labels != null && params.labels.isNotEmpty()) {
                filteredConversations = filteredConversations.filter { conversation ->
                    params.labels.any { labelPath ->
                        conversation.labels.any { it.path == labelPath }
                    }
                }
            }
            
            // Apply metadata filter if provided
            if (params.meta != null && params.meta.isNotEmpty()) {
                filteredConversations = filteredConversations.filter { conversation ->
                    params.meta.all { (key, value) ->
                        conversation.meta[key] == value
                    }
                }
            }
            
            // Apply sorting
            filteredConversations = if (params.order.equals("asc", ignoreCase = true)) {
                filteredConversations.sortedBy { it.createdAt }
            } else {
                filteredConversations.sortedByDescending { it.createdAt }
            }
            
            // Apply "after" cursor pagination
            if (params.after != null) {
                val afterConversation = getConversation(params.after)
                if (afterConversation != null) {
                    val afterTimestamp = afterConversation.createdAt
                    filteredConversations = if (params.order.equals("asc", ignoreCase = true)) {
                        filteredConversations.filter { it.createdAt > afterTimestamp }
                    } else {
                        filteredConversations.filter { it.createdAt < afterTimestamp }
                    }
                }
            }
            
            // Apply "before" cursor pagination
            if (params.before != null) {
                val beforeConversation = getConversation(params.before)
                if (beforeConversation != null) {
                    val beforeTimestamp = beforeConversation.createdAt
                    filteredConversations = if (params.order.equals("asc", ignoreCase = true)) {
                        filteredConversations.filter { it.createdAt < beforeTimestamp }
                    } else {
                        filteredConversations.filter { it.createdAt > beforeTimestamp }
                    }
                }
            }
            
            // Apply limit
            return filteredConversations.take(params.limit)
        } catch (e: Exception) {
            logger.error(e) { "Error listing conversations with params: $params" }
            return emptyList()
        }
    }

    /**
     * Update a conversation.
     *
     * @param conversation The conversation to update
     * @return The updated conversation, or null if not found
     */
    override suspend fun updateConversation(conversation: Conversation): Conversation? {
        return try {
            if (!conversations.containsKey(conversation.id)) {
                logger.info { "Conversation not found for update with ID: ${conversation.id}" }
                return null
            }
            
            conversations[conversation.id] = conversation
            logger.info { "Updated conversation with ID: ${conversation.id}" }
            conversation
        } catch (e: Exception) {
            logger.error(e) { "Error updating conversation with ID: ${conversation.id}" }
            null
        }
    }

    /**
     * Delete a conversation.
     *
     * @param conversationId The ID of the conversation to delete
     * @return True if the conversation was deleted, false otherwise
     */
    override suspend fun deleteConversation(conversationId: String): Boolean =
        try {
            val removed = conversations.remove(conversationId) != null
            
            if (removed) {
                logger.info { "Deleted conversation with ID: $conversationId" }
            } else {
                logger.info { "No conversation found to delete with ID: $conversationId" }
            }
            
            removed
        } catch (e: Exception) {
            logger.error(e) { "Error deleting conversation with ID: $conversationId" }
            false
        }
} 
