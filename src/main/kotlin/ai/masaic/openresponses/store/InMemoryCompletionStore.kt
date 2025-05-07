package ai.masaic.openresponses.store

import ai.masaic.openresponses.api.client.CompletionStore
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessageParam
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// Data class to hold stored info internally for the in-memory store
data class InMemoryStoredCompletion(
    val completion: ChatCompletion,
    val messages: List<ChatCompletionMessageParam>,
    val context: CompletionToolRequestContext?,
)

@Component
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryCompletionStore : CompletionStore {
    private val logger = KotlinLogging.logger {}

    // Store completion and messages together
    private val store = ConcurrentHashMap<String, InMemoryStoredCompletion>()

    override suspend fun storeCompletion(
        completion: ChatCompletion,
        messages: List<ChatCompletionMessageParam>,
        context: CompletionToolRequestContext?,
    ): ChatCompletion {
        val completionId = completion.id()
        if (completionId.isBlank()) {
            logger.warn { "Attempted to store completion without a valid ID. Skipping." }
            return completion // Return original if not stored
        }
        val dataToStore =
            InMemoryStoredCompletion(
                completion = completion,
                messages = messages, // Store a copy
                context = context, // Store context
            )
        store[completionId] = dataToStore
        logger.debug { "Stored completion with ID: $completionId in memory." }
        return completion // Return the stored completion
    }

    override suspend fun getCompletion(completionId: String): ChatCompletion? {
        logger.debug { "Retrieving completion with ID: $completionId from memory." }
        return store[completionId]?.completion
    }

    override suspend fun getMessages(completionId: String): List<ChatCompletionMessageParam> {
        logger.debug { "Retrieving messages for completion ID: $completionId from memory." }
        return store[completionId]?.messages ?: emptyList()
    }

    override suspend fun deleteCompletion(completionId: String): Boolean {
        logger.debug { "Deleting completion with ID: $completionId from memory." }
        return store.remove(completionId) != null
    }
} 
