package ai.masaic.improved.repository

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.model.ListConversationsParams
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.Aggregation.*
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.remove
import org.springframework.stereotype.Repository
import org.bson.Document
import org.springframework.data.mongodb.core.aggregation.DateOperators
import org.springframework.data.mongodb.core.aggregation.Fields
import java.time.Instant

/**
 * MongoDB implementation of ConversationRepository.
 *
 * This implementation stores conversation data in MongoDB.
 * It uses reactive MongoDB with Kotlin coroutines for non-blocking operations.
 * 
 * It is only enabled when open-responses.store.type=mongodb
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "mongodb")
class MongoConversationRepository(
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
) : ConversationRepository {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val CONVERSATION_COLLECTION = "labelled_conversations"
    }

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
            
            return reactiveMongoTemplate.save(conversationWithId, CONVERSATION_COLLECTION).awaitFirst().also {
                logger.info { "Saved conversation with ID: ${it.id}" }
            }
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
            reactiveMongoTemplate.findById<Conversation>(conversationId, CONVERSATION_COLLECTION).awaitFirstOrNull()
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
            val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
            reactiveMongoTemplate.find<Conversation>(query, CONVERSATION_COLLECTION).collectList().awaitSingle()
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
            // Start with a base query
            val query = Query()
            
            // Add resolved filtering if provided
            if (params.resolved != null) {
                query.addCriteria(Criteria.where("resolved").`is`(params.resolved))
            }
            
            // Add labels filtering if provided
            if (params.labels != null && params.labels.isNotEmpty()) {
                val labelsCriteria = Criteria()
                params.labels.forEach { labelPath ->
                    labelsCriteria.orOperator(
                        Criteria.where("labels").elemMatch(Criteria.where("path").`is`(labelPath))
                    )
                }
                query.addCriteria(labelsCriteria)
            }
            
            // Add metadata filtering if provided
            if (params.meta != null && params.meta.isNotEmpty()) {
                val criteria = Criteria()
                params.meta.forEach { (key, value) ->
                    criteria.and("meta.$key").`is`(value)
                }
                query.addCriteria(criteria)
            }
            
            // Add pagination filtering
            if (params.after != null) {
                val afterConversation = getConversation(params.after)
                if (afterConversation != null) {
                    val createdAtCriteria =
                        if (params.order.equals("asc", ignoreCase = true)) {
                            Criteria.where("createdAt").gt(afterConversation.createdAt)
                        } else {
                            Criteria.where("createdAt").lt(afterConversation.createdAt)
                        }
                    query.addCriteria(createdAtCriteria)
                }
            }
            
            if (params.before != null) {
                val beforeConversation = getConversation(params.before)
                if (beforeConversation != null) {
                    val createdAtCriteria =
                        if (params.order.equals("asc", ignoreCase = true)) {
                            Criteria.where("createdAt").lt(beforeConversation.createdAt)
                        } else {
                            Criteria.where("createdAt").gt(beforeConversation.createdAt)
                        }
                    query.addCriteria(createdAtCriteria)
                }
            }
            
            // Set sort order
            val sort =
                if (params.order.equals("asc", ignoreCase = true)) {
                    Sort.by(Sort.Direction.ASC, "createdAt")
                } else {
                    Sort.by(Sort.Direction.DESC, "createdAt")
                }
            query.with(sort)
            
            // Set limit
            query.limit(params.limit)
            
            // Execute query
            return reactiveMongoTemplate.find<Conversation>(query, CONVERSATION_COLLECTION).collectList().awaitSingle()
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
            // Make sure the conversation exists before updating
            val existingConversation = getConversation(conversation.id)
            if (existingConversation == null) {
                logger.info { "Conversation not found for update with ID: ${conversation.id}" }
                return null
            }
            
            reactiveMongoTemplate.save(conversation, CONVERSATION_COLLECTION).awaitFirst().also {
                logger.info { "Updated conversation with ID: ${it.id}" }
            }
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
            val result = reactiveMongoTemplate.remove<Conversation>(
                Query(Criteria.where("_id").`is`(conversationId)),
                CONVERSATION_COLLECTION
            ).awaitFirst()
            
            val deleted = result.deletedCount > 0
            if (deleted) {
                logger.info { "Deleted conversation with ID: $conversationId" }
            } else {
                logger.info { "No conversation found to delete with ID: $conversationId" }
            }
            
            deleted
        } catch (e: Exception) {
            logger.error(e) { "Error deleting conversation with ID: $conversationId" }
            false
        }

    /**
     * Get conversations that match a specific label path with a limit.
     *
     * @param labelPath The path to match against conversation labels
     * @param limit The maximum number of conversations to return
     * @return A list of conversations that match the label path
     */
    override suspend fun getConversations(labelPath: String, limit: Int): List<Conversation> {
        try {
            val query = Query()
            query.addCriteria(Criteria.where("labels").elemMatch(Criteria.where("path").`is`(labelPath)))
            query.with(Sort.by(Sort.Direction.DESC, "createdAt"))
            query.limit(limit)
            
            return reactiveMongoTemplate.find<Conversation>(query, CONVERSATION_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            logger.error(e) { "Error finding conversations with label path: $labelPath" }
            return emptyList()
        }
    }

    suspend fun getGenericLabels(labelPrefix: String = ""): List<GenericLabel> {
        // 1) $unwind: "$labels"
        // 2) $group by labels.path, summing 1 into "count"
        // 3) $sort by count descending

        val agg: Aggregation = if(labelPrefix.isNotEmpty()) {
            newAggregation(
                unwind("labels"),
                match(Criteria.where("labels.path").regex("^$labelPrefix")),
                group("labels.path").count().`as`("count"),
                sort(Sort.by(Sort.Direction.DESC, "count"))
            )
        } else {
            newAggregation(
                unwind("labels"),
                group("labels.path").count().`as`("count"),
                sort(Sort.by(Sort.Direction.DESC, "count"))
            )
        }

        // run the aggregation against the "labelled_conversations" collection
        val docs: List<Document> = reactiveMongoTemplate
            .aggregate(agg, CONVERSATION_COLLECTION, Document::class.java)
            .collectList()
            .awaitSingle()

        return docs.map {
            GenericLabel(it.getString("_id"), it.getInteger("count"))
        }
    }

    suspend fun aggregateLabels(): List<Document> {
        val start = Instant.parse("2025-05-01T00:00:00.000Z")
        val end   = Instant.parse("2025-05-31T00:00:00.000Z")

        // 1) date filter on the root document
        val dateMatch = Criteria.where("createdAt").gte(start).lte(end)
        // 2) unwind labels, then filter to only your domain/final labels
        val labelMatch = Criteria.where("labels.path").regex("^domain")
            .and("labels.status").`is`("final")

        val pipeline = newAggregation(
            match(dateMatch),
            unwind("labels"),
            match(labelMatch),

            // 3) project both the day-string and the label path
            project()
                .and(
                    DateOperators.DateToString
                        .dateOf("\$createdAt")
                        .toString("%Y-%m-%d")
                        .withTimezone(DateOperators.Timezone.valueOf("UTC"))
                ).`as`("day")
                .and("labels.path").`as`("path"),

            // 4) group by the composite key { day, path } and count
            group(Fields.from(
                Fields.field("day"),
                Fields.field("path")
            )).count().`as`("count"),

            // 5) flatten the _id so we end up with top‐level "day" and "path"
            project("count")
                .and("_id.path").`as`("path")
                .and("_id.day").`as`("day"),

            // 6) sort by day ascending (and maybe count descending if you like)
            sort(Sort.by(Sort.Direction.ASC, "day"))
        )

        return reactiveMongoTemplate
            .aggregate(pipeline, "labelled_conversations", Document::class.java)
            .collectList()
            .awaitSingle()
    }
}

data class GenericLabel(val path: String, val count: Int)
