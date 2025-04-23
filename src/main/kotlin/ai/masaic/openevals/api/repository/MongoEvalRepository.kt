package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.Eval
import ai.masaic.openevals.api.model.ListEvalsParams
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.remove
import org.springframework.stereotype.Repository

/**
 * MongoDB implementation of EvalRepository.
 *
 * This implementation stores evaluation data in MongoDB.
 * It uses reactive MongoDB with Kotlin coroutines for non-blocking operations.
 * 
 * It is only enabled when open-responses.store.eval.repository.type=mongodb
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "mongodb")
class MongoEvalRepository(
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
) : EvalRepository {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val EVAL_COLLECTION = "evals"
    }

    /**
     * Create a new evaluation.
     * 
     * @param eval The evaluation to create
     * @return The created evaluation
     */
    override suspend fun createEval(eval: Eval): Eval {
        try {
            // Generate an eval_id if it's not provided or empty
            val evalWithId =
                if (eval.id.isBlank()) {
                    eval.copy(id = "eval_${java.util.UUID.randomUUID().toString().replace("-", "")}")
                } else {
                    eval
                }
            
            return reactiveMongoTemplate.save(evalWithId, EVAL_COLLECTION).awaitFirst().also {
                logger.info { "Saved eval with ID: ${it.id}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error saving eval" }
            throw e
        }
    }

    /**
     * Get an evaluation by ID.
     *
     * @param evalId The ID of the evaluation to retrieve
     * @return The evaluation, or null if not found
     */
    override suspend fun getEval(evalId: String): Eval? =
        try {
            reactiveMongoTemplate.findById<Eval>(evalId, EVAL_COLLECTION).awaitFirstOrNull()
        } catch (e: Exception) {
            logger.error(e) { "Error getting eval with ID: $evalId" }
            null
        }

    /**
     * List all evaluations.
     *
     * @return A list of all evaluations
     */
    override suspend fun listEvals(): List<Eval> =
        try {
            val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
            reactiveMongoTemplate.find<Eval>(query, EVAL_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            logger.error(e) { "Error listing evals" }
            emptyList()
        }

    /**
     * List evaluations with pagination and filtering.
     *
     * @param params The parameters for listing evaluations
     * @return A list of evaluations that match the criteria
     */
    override suspend fun listEvals(params: ListEvalsParams): List<Eval> {
        try {
            // Start with a base query
            val query = Query()
            
            // Add metadata filtering if provided
            if (params.metadata != null && params.metadata.isNotEmpty()) {
                val criteria = Criteria()
                params.metadata.forEach { (key, value) ->
                    criteria.and("metadata.$key").`is`(value)
                }
                query.addCriteria(criteria)
            }
            
            // Add pagination filtering
            if (params.after != null) {
                val afterEval = getEval(params.after)
                if (afterEval != null) {
                    val createdAtCriteria =
                        if (params.order.equals("asc", ignoreCase = true)) {
                            Criteria.where("createdAt").gt(afterEval.createdAt)
                        } else {
                            Criteria.where("createdAt").lt(afterEval.createdAt)
                        }
                    query.addCriteria(createdAtCriteria)
                }
            }
            
            if (params.before != null) {
                val beforeEval = getEval(params.before)
                if (beforeEval != null) {
                    val createdAtCriteria =
                        if (params.order.equals("asc", ignoreCase = true)) {
                            Criteria.where("createdAt").lt(beforeEval.createdAt)
                        } else {
                            Criteria.where("createdAt").gt(beforeEval.createdAt)
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
            return reactiveMongoTemplate.find<Eval>(query, EVAL_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            logger.error(e) { "Error listing evals with params: $params" }
            return emptyList()
        }
    }

    /**
     * Delete an evaluation.
     *
     * @param evalId The ID of the evaluation to delete
     * @return True if the evaluation was deleted, false otherwise
     */
    override suspend fun deleteEval(evalId: String): Boolean =
        try {
            val query = Query(Criteria.where("_id").`is`(evalId))
            val result = reactiveMongoTemplate.remove<Eval>(query, EVAL_COLLECTION).awaitFirst()
            val deleted = result.deletedCount > 0
            
            if (deleted) {
                logger.info { "Deleted eval with ID: $evalId" }
            } else {
                logger.info { "No eval found to delete with ID: $evalId" }
            }
            
            deleted
        } catch (e: Exception) {
            logger.error(e) { "Error deleting eval with ID: $evalId" }
            false
        }

    /**
     * Update an evaluation.
     *
     * @param eval The evaluation to update
     * @return The updated evaluation, or null if not found
     */
    override suspend fun updateEval(eval: Eval): Eval? {
        return try {
            // Make sure the eval exists before updating
            val existingEval = getEval(eval.id)
            if (existingEval == null) {
                logger.info { "Eval not found for update with ID: ${eval.id}" }
                return null
            }
            
            reactiveMongoTemplate.save(eval, EVAL_COLLECTION).awaitFirst().also {
                logger.info { "Updated eval with ID: ${it.id}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error updating eval with ID: ${eval.id}" }
            null
        }
    }
} 
