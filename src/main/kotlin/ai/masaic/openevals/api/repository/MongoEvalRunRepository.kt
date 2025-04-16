package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.EvalRunStatus
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
 * MongoDB implementation of EvalRunRepository.
 *
 * This implementation stores evaluation run data in MongoDB.
 * It uses reactive MongoDB with Kotlin coroutines for non-blocking operations.
 * 
 * It is only enabled when open-responses.store.eval-run.repository.type=mongodb
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "mongodb")
class MongoEvalRunRepository(
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
) : EvalRunRepository {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val EVAL_RUN_COLLECTION = "eval_runs"
    }

    /**
     * Create a new evaluation run.
     *
     * @param evalRun The evaluation run to create
     * @return The created evaluation run
     */
    override suspend fun createEvalRun(evalRun: EvalRun): EvalRun {
        try {
            // Generate a run_id if it's not provided or empty
            val evalRunWithId =
                if (evalRun.id.isBlank()) {
                    evalRun.copy(id = "run_${java.util.UUID.randomUUID().toString().replace("-", "")}")
                } else {
                    evalRun
                }
            
            return reactiveMongoTemplate.save(evalRunWithId, EVAL_RUN_COLLECTION).awaitFirst().also {
                logger.info { "Saved eval run with ID: ${it.id} for eval ${it.evalId}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error saving eval run" }
            throw e
        }
    }

    /**
     * Get an evaluation run by ID.
     *
     * @param evalRunId The ID of the evaluation run to retrieve
     * @return The evaluation run, or null if not found
     */
    override suspend fun getEvalRun(evalRunId: String): EvalRun? =
        try {
            reactiveMongoTemplate.findById<EvalRun>(evalRunId, EVAL_RUN_COLLECTION).awaitFirstOrNull()
        } catch (e: Exception) {
            logger.error(e) { "Error getting eval run with ID: $evalRunId" }
            null
        }

    /**
     * List all evaluation runs.
     *
     * @return A list of all evaluation runs
     */
    override suspend fun listEvalRuns(): List<EvalRun> =
        try {
            val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
            reactiveMongoTemplate.find<EvalRun>(query, EVAL_RUN_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            logger.error(e) { "Error listing eval runs" }
            emptyList()
        }

    /**
     * List evaluation runs for a specific eval.
     *
     * @param evalId The ID of the eval
     * @return A list of evaluation runs for the specified eval
     */
    override suspend fun listEvalRunsByEvalId(evalId: String): List<EvalRun> =
        try {
            val query =
                Query(Criteria.where("evalId").`is`(evalId))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            reactiveMongoTemplate.find<EvalRun>(query, EVAL_RUN_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            logger.error(e) { "Error listing eval runs for eval ID: $evalId" }
            emptyList()
        }

    /**
     * List evaluation runs for a specific eval with pagination, ordering, and status filtering.
     *
     * @param evalId The ID of the eval
     * @param after Identifier for the last run from the previous pagination request
     * @param limit Number of runs to retrieve
     * @param order Sort order for runs by timestamp (asc or desc)
     * @param status Optional status filter
     * @return A list of evaluation runs for the specified eval
     */
    override suspend fun listEvalRunsByEvalId(
        evalId: String,
        after: String?,
        limit: Int,
        order: String,
        status: EvalRunStatus?,
    ): List<EvalRun> =
        try {
            // Start with a base query that filters by evalId
            val criteria = Criteria.where("evalId").`is`(evalId)
            
            // Add status filtering if provided
            if (status != null) {
                criteria.and("status").`is`(status)
            }
            
            // Create the query
            var query = Query(criteria)
            
            // Add pagination filtering
            if (after != null) {
                val afterRun = getEvalRun(after)
                if (afterRun != null) {
                    val createdAtCriteria =
                        if (order.equals("asc", ignoreCase = true)) {
                            Criteria.where("createdAt").gt(afterRun.createdAt)
                        } else {
                            Criteria.where("createdAt").lt(afterRun.createdAt)
                        }
                    query.addCriteria(createdAtCriteria)
                }
            }
            
            // Set sort order
            val sort =
                if (order.equals("asc", ignoreCase = true)) {
                    Sort.by(Sort.Direction.ASC, "createdAt")
                } else {
                    Sort.by(Sort.Direction.DESC, "createdAt")
                }
            query = query.with(sort)
            
            // Set limit
            query = query.limit(limit)
            
            // Execute query
            reactiveMongoTemplate.find<EvalRun>(query, EVAL_RUN_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            logger.error(e) { "Error listing eval runs for eval ID: $evalId with filters" }
            emptyList()
        }

    /**
     * Update an evaluation run.
     *
     * @param evalRun The evaluation run to update
     * @return The updated evaluation run
     */
    override suspend fun updateEvalRun(evalRun: EvalRun): EvalRun {
        try {
            // Check if the eval run exists
            if (getEvalRun(evalRun.id) == null) {
                logger.error { "Cannot update non-existent eval run with ID: ${evalRun.id}" }
                throw IllegalArgumentException("Evaluation run not found with ID: ${evalRun.id}")
            }
            
            return reactiveMongoTemplate.save(evalRun, EVAL_RUN_COLLECTION).awaitFirst().also {
                logger.info { "Updated eval run with ID: ${it.id}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error updating eval run with ID: ${evalRun.id}" }
            throw e
        }
    }

    /**
     * Delete an evaluation run.
     *
     * @param evalRunId The ID of the evaluation run to delete
     * @return True if the evaluation run was deleted, false otherwise
     */
    override suspend fun deleteEvalRun(evalRunId: String): Boolean =
        try {
            val query = Query(Criteria.where("_id").`is`(evalRunId))
            val result = reactiveMongoTemplate.remove<EvalRun>(query, EVAL_RUN_COLLECTION).awaitFirst()
            val deleted = result.deletedCount > 0
            
            if (deleted) {
                logger.info { "Deleted eval run with ID: $evalRunId" }
            } else {
                logger.info { "No eval run found to delete with ID: $evalRunId" }
            }
            
            deleted
        } catch (e: Exception) {
            logger.error(e) { "Error deleting eval run with ID: $evalRunId" }
            false
        }
} 
