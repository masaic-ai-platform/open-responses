package ai.masaic.openresponses.store

import ai.masaic.openevals.api.repository.CompletionDocumentRepository
import ai.masaic.openresponses.api.client.CompletionStore // Import interface
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessageParam
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.stereotype.Component

@Document(collection = "chat_completions")
data class CompletionDocument(
    @Id
    val id: String, // Corresponds to completion.id()
    val completionJson: String,
    val messagesJson: String,
    val aliasMapJson: String, // Store alias map as JSON string
    val createdAt: Long = System.currentTimeMillis(), // Optional timestamp
)

@Component
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "mongodb")
class MongoCompletionStore(
    private val repository: CompletionDocumentRepository,
    private val objectMapper: ObjectMapper,
) : CompletionStore {
    private val logger = KotlinLogging.logger {}

    override suspend fun storeCompletion(
        completion: ChatCompletion,
        messages: List<ChatCompletionMessageParam>,
        context: CompletionToolRequestContext?,
    ): ChatCompletion { // Method signature matches interface
        val completionId = completion.id()
        if (completionId.isBlank()) {
            logger.warn { "Attempted to store completion without a valid ID. Skipping." }
            return completion // Return original if not stored
        }

        try {
            val completionJson = objectMapper.writeValueAsString(completion)
            val messagesJson = objectMapper.writeValueAsString(messages)
            // Serialize alias map if context is provided
            val aliasMapJson = context?.aliasMap?.let { objectMapper.writeValueAsString(it) } ?: "{}"

            val document =
                CompletionDocument(
                    id = completionId,
                    completionJson = completionJson,
                    messagesJson = messagesJson,
                    aliasMapJson = aliasMapJson,
                )
            repository.save(document).awaitSingle()
            logger.debug { "Stored completion with ID: $completionId in MongoDB." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize or store completion data for ID: $completionId" }
            // Optionally rethrow or handle, but return original completion on error
            return completion
        }
        return completion // Return stored completion
    }

    // Internal helper to get the document and deserialize
    private suspend fun getDeserializedCompletion(completionId: String): ChatCompletion? {
        val document = repository.findById(completionId).awaitSingleOrNull()
        return document?.let {
            try {
                objectMapper.readValue<ChatCompletion>(it.completionJson)
            } catch (e: Exception) {
                logger.error(e) { "Failed to deserialize stored completion JSON for ID: $completionId" }
                null
            }
        }
    }

    // Internal helper to get the document and deserialize messages
    private suspend fun getDeserializedMessages(completionId: String): List<ChatCompletionMessageParam> {
        val document = repository.findById(completionId).awaitSingleOrNull()
        return document?.let {
            try {
                objectMapper.readValue<List<ChatCompletionMessageParam>>(it.messagesJson)
            } catch (e: Exception) {
                logger.error(e) { "Failed to deserialize stored messages JSON for ID: $completionId" }
                null
            }
        } ?: emptyList()
    }

    override suspend fun getCompletion(completionId: String): ChatCompletion? {
        logger.debug { "Retrieving completion with ID: $completionId from MongoDB." }
        return getDeserializedCompletion(completionId)
    }

    override suspend fun getMessages(completionId: String): List<ChatCompletionMessageParam> {
        logger.debug { "Retrieving messages for completion ID: $completionId from MongoDB." }
        return getDeserializedMessages(completionId)
    }

    override suspend fun deleteCompletion(completionId: String): Boolean =
        try {
            // Check if document exists before deleting to return accurate boolean
            val exists = repository.existsById(completionId).awaitSingle()
            if (exists) {
                repository.deleteById(completionId).awaitSingleOrNull() 
                logger.debug { "Deleted completion ID: $completionId from MongoDB." }
                true
            } else {
                logger.debug { "Completion ID: $completionId not found for deletion in MongoDB." }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Error deleting completion ID: $completionId from MongoDB." }
            false
        }
} 
