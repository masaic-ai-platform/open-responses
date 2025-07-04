package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.Eval
import ai.masaic.openevals.api.model.ListEvalsParams
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository interface for managing evaluations.
 * Uses suspend functions for non-blocking operations.
 */
interface EvalRepository {
    /**
     * Create a new evaluation.
     *
     * @param eval The evaluation to create
     * @return The created evaluation
     */
    suspend fun createEval(eval: Eval): Eval

    /**
     * Get an evaluation by ID.
     *
     * @param evalId The ID of the evaluation to retrieve
     * @return The evaluation, or null if not found
     */
    suspend fun getEval(evalId: String): Eval?

    /**
     * List all evaluations.
     *
     * @return A list of all evaluations
     */
    suspend fun listEvals(): List<Eval>

    /**
     * List evaluations with pagination and filtering.
     *
     * @param params The parameters for listing evaluations
     * @return A list of evaluations that match the criteria
     */
    suspend fun listEvals(params: ListEvalsParams): List<Eval>

    /**
     * Delete an evaluation.
     *
     * @param evalId The ID of the evaluation to delete
     * @return True if the evaluation was deleted, false otherwise
     */
    suspend fun deleteEval(evalId: String): Boolean

    /**
     * Update an evaluation.
     *
     * @param eval The evaluation to update
     * @return The updated evaluation, or null if not found
     */
    suspend fun updateEval(eval: Eval): Eval?
}

/**
 * In-memory implementation of the EvalRepository interface.
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryEvalRepository : EvalRepository {
    private val evaluations = ConcurrentHashMap<String, Eval>()

    override suspend fun createEval(eval: Eval): Eval {
        val evalId = "eval_${UUID.randomUUID().toString().replace("-", "")}"
        val newEval = eval.copy(id = evalId)
        evaluations[evalId] = newEval
        return newEval
    }

    override suspend fun getEval(evalId: String): Eval? = evaluations[evalId]

    override suspend fun listEvals(): List<Eval> = evaluations.values.toList()

    override suspend fun listEvals(params: ListEvalsParams): List<Eval> {
        var result = evaluations.values.toList()
        
        // Filter by metadata if provided
        if (params.metadata != null && params.metadata.isNotEmpty()) {
            result =
                result.filter { eval ->
                    params.metadata.all { (key, value) ->
                        eval.metadata?.get(key) == value
                    }
                }
        }
        
        // Sort by createdAt
        result =
            if (params.order.equals("asc", ignoreCase = true)) {
                result.sortedBy { it.createdAt }
            } else {
                result.sortedByDescending { it.createdAt }
            }
        
        // Apply cursor-based pagination
        if (params.after != null) {
            val afterEval = evaluations[params.after]
            if (afterEval != null) {
                result =
                    if (params.order.equals("asc", ignoreCase = true)) {
                        result.filter { it.createdAt > afterEval.createdAt }
                    } else {
                        result.filter { it.createdAt < afterEval.createdAt }
                    }
            }
        }
        
        if (params.before != null) {
            val beforeEval = evaluations[params.before]
            if (beforeEval != null) {
                result =
                    if (params.order.equals("asc", ignoreCase = true)) {
                        result.filter { it.createdAt < beforeEval.createdAt }
                    } else {
                        result.filter { it.createdAt > beforeEval.createdAt }
                    }
            }
        }
        
        // Apply limit
        return result.take(params.limit)
    }

    override suspend fun deleteEval(evalId: String): Boolean = evaluations.remove(evalId) != null

    override suspend fun updateEval(eval: Eval): Eval? {
        // Check if the eval exists
        if (!evaluations.containsKey(eval.id)) {
            return null
        }
        
        // Update the eval
        evaluations[eval.id] = eval
        
        return eval
    }
} 
