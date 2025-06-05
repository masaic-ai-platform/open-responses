package ai.masaic.improved.repository

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.model.ListConversationsParams
import ai.masaic.improved.service.ConversationGraphService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository

/**
 * A decorator for ConversationRepository that adds graph database functionality.
 * 
 * This repository wraps the existing conversation repository implementations and 
 * automatically adds conversations to the graph database whenever they are created,
 * updated, or deleted. It follows the decorator pattern to extend functionality
 * without modifying existing code.
 */
@Repository
@Primary
@ConditionalOnProperty(name = ["improved.graph.enabled"], havingValue = "true", matchIfMissing = true)
class GraphEnabledConversationRepository(
    @Qualifier("baseConversationRepository") private val baseRepository: ConversationRepository,
    private val graphService: ConversationGraphService,
) : ConversationRepository {
    private val logger = KotlinLogging.logger {}

    /**
     * Create a new conversation and add it to the graph.
     *
     * @param conversation The conversation to create
     * @return The created conversation
     */
    override suspend fun createConversation(conversation: Conversation): Conversation =
        try {
            // Create in the base repository first
            val createdConversation = baseRepository.createConversation(conversation)
            
            // Then add to graph if it has labels
            if (createdConversation.labels.isNotEmpty()) {
                try {
                    graphService.addConversationToGraph(createdConversation)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to add conversation ${createdConversation.id} to graph, but conversation was created successfully" }
                    // Don't fail the entire operation if graph update fails
                }
            }
            
            createdConversation
        } catch (e: Exception) {
            logger.error(e) { "Error creating conversation: ${e.message}" }
            throw e
        }

    /**
     * Get a conversation by ID.
     *
     * @param conversationId The ID of the conversation to retrieve
     * @return The conversation, or null if not found
     */
    override suspend fun getConversation(conversationId: String): Conversation? = baseRepository.getConversation(conversationId)

    /**
     * List all conversations.
     *
     * @return A list of all conversations
     */
    override suspend fun listConversations(): List<Conversation> = baseRepository.listConversations()

    /**
     * List conversations with pagination and filtering.
     *
     * @param params The parameters for listing conversations
     * @return A list of conversations that match the criteria
     */
    override suspend fun listConversations(params: ListConversationsParams): List<Conversation> = baseRepository.listConversations(params)

    /**
     * Get conversations that match a specific label path with a limit.
     *
     * @param labelPath The path to match against conversation labels
     * @param limit The maximum number of conversations to return
     * @return A list of conversations that match the label path
     */
    override suspend fun getConversations(
        labelPath: String,
        limit: Int,
    ): List<Conversation> = baseRepository.getConversations(labelPath, limit)

    /**
     * Update a conversation and sync with the graph.
     *
     * @param conversation The conversation to update
     * @return The updated conversation, or null if not found
     */
    override suspend fun updateConversation(conversation: Conversation): Conversation? =
        try {
            // Update in the base repository first
            val updatedConversation = baseRepository.updateConversation(conversation)
            
            // Then update in graph if successful and has labels
            if (updatedConversation != null && updatedConversation.labels.isNotEmpty()) {
                try {
                    graphService.updateConversationInGraph(updatedConversation)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to update conversation ${updatedConversation.id} in graph, but conversation was updated successfully" }
                    // Don't fail the entire operation if graph update fails
                }
            }
            
            updatedConversation
        } catch (e: Exception) {
            logger.error(e) { "Error updating conversation: ${e.message}" }
            throw e
        }

    /**
     * Delete a conversation and remove it from the graph.
     *
     * @param conversationId The ID of the conversation to delete
     * @return True if the conversation was deleted, false otherwise
     */
    override suspend fun deleteConversation(conversationId: String): Boolean =
        try {
            // Delete from the base repository first
            val deleted = baseRepository.deleteConversation(conversationId)
            
            // Then remove from graph if successful
            if (deleted) {
                try {
                    graphService.removeConversationFromGraph(conversationId)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to remove conversation $conversationId from graph, but conversation was deleted successfully" }
                    // Don't fail the entire operation if graph update fails
                }
            }
            
            deleted
        } catch (e: Exception) {
            logger.error(e) { "Error deleting conversation: ${e.message}" }
            throw e
        }
} 
