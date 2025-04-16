package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.EvalRunStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository interface for managing evaluation runs.
 * Uses suspend functions for non-blocking operations.
 */
interface EvalRunRepository {
    /**
     * Create a new evaluation run.
     *
     * @param evalRun The evaluation run to create
     * @return The created evaluation run
     */
    suspend fun createEvalRun(evalRun: EvalRun): EvalRun

    /**
     * Get an evaluation run by ID.
     *
     * @param evalRunId The ID of the evaluation run to retrieve
     * @return The evaluation run, or null if not found
     */
    suspend fun getEvalRun(evalRunId: String): EvalRun?

    /**
     * List all evaluation runs.
     *
     * @return A list of all evaluation runs
     */
    suspend fun listEvalRuns(): List<EvalRun>

    /**
     * List evaluation runs for a specific eval.
     *
     * @param evalId The ID of the eval
     * @return A list of evaluation runs for the specified eval
     */
    suspend fun listEvalRunsByEvalId(evalId: String): List<EvalRun>

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
    suspend fun listEvalRunsByEvalId(
        evalId: String,
        after: String?,
        limit: Int,
        order: String,
        status: EvalRunStatus?,
    ): List<EvalRun>

    /**
     * Update an evaluation run.
     *
     * @param evalRun The evaluation run to update
     * @return The updated evaluation run
     */
    suspend fun updateEvalRun(evalRun: EvalRun): EvalRun

    /**
     * Delete an evaluation run.
     *
     * @param evalRunId The ID of the evaluation run to delete
     * @return True if the evaluation run was deleted, false otherwise
     */
    suspend fun deleteEvalRun(evalRunId: String): Boolean
}

/**
 * In-memory implementation of the EvalRunRepository interface.
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryEvalRunRepository : EvalRunRepository {
    private val evalRuns = ConcurrentHashMap<String, EvalRun>()

    override suspend fun createEvalRun(evalRun: EvalRun): EvalRun {
        val evalRunId = "run_${UUID.randomUUID().toString().replace("-", "")}"
        val newEvalRun = evalRun.copy(id = evalRunId)
        evalRuns[evalRunId] = newEvalRun
        return newEvalRun
    }

    override suspend fun getEvalRun(evalRunId: String): EvalRun? = evalRuns[evalRunId]

    override suspend fun listEvalRuns(): List<EvalRun> = evalRuns.values.toList()

    override suspend fun listEvalRunsByEvalId(evalId: String): List<EvalRun> = evalRuns.values.filter { it.evalId == evalId }

    override suspend fun listEvalRunsByEvalId(
        evalId: String,
        after: String?,
        limit: Int,
        order: String,
        status: EvalRunStatus?,
    ): List<EvalRun> {
        // Filter runs by evalId and status if provided
        var filteredRuns = evalRuns.values.filter { it.evalId == evalId }
        
        if (status != null) {
            filteredRuns = filteredRuns.filter { it.status == status }
        }
        
        // If after parameter is provided, filter runs created after that run
        if (after != null) {
            val afterRun = evalRuns[after]
            if (afterRun != null) {
                filteredRuns =
                    filteredRuns.filter { 
                        it.createdAt > afterRun.createdAt 
                    }
            }
        }
        
        // Sort runs by creation timestamp
        val sortedRuns =
            if (order == "asc") {
                filteredRuns.sortedBy { it.createdAt }
            } else {
                filteredRuns.sortedByDescending { it.createdAt }
            }
        
        // Apply limit
        return sortedRuns.take(limit)
    }

    override suspend fun updateEvalRun(evalRun: EvalRun): EvalRun {
        if (!evalRuns.containsKey(evalRun.id)) {
            throw IllegalArgumentException("Evaluation run not found with ID: ${evalRun.id}")
        }
        evalRuns[evalRun.id] = evalRun
        return evalRun
    }

    override suspend fun deleteEvalRun(evalRunId: String): Boolean = evalRuns.remove(evalRunId) != null
} 
