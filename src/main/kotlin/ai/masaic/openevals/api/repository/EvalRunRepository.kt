package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.EvalRun
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository interface for managing evaluation runs.
 */
interface EvalRunRepository {
    /**
     * Create a new evaluation run.
     *
     * @param evalRun The evaluation run to create
     * @return The created evaluation run
     */
    fun createEvalRun(evalRun: EvalRun): EvalRun

    /**
     * Get an evaluation run by ID.
     *
     * @param evalRunId The ID of the evaluation run to retrieve
     * @return The evaluation run, or null if not found
     */
    fun getEvalRun(evalRunId: String): EvalRun?

    /**
     * List all evaluation runs.
     *
     * @return A list of all evaluation runs
     */
    fun listEvalRuns(): List<EvalRun>
    
    /**
     * List evaluation runs for a specific eval.
     *
     * @param evalId The ID of the eval
     * @return A list of evaluation runs for the specified eval
     */
    fun listEvalRunsByEvalId(evalId: String): List<EvalRun>

    /**
     * Update an evaluation run.
     *
     * @param evalRun The evaluation run to update
     * @return The updated evaluation run
     */
    fun updateEvalRun(evalRun: EvalRun): EvalRun

    /**
     * Delete an evaluation run.
     *
     * @param evalRunId The ID of the evaluation run to delete
     * @return True if the evaluation run was deleted, false otherwise
     */
    fun deleteEvalRun(evalRunId: String): Boolean
}

/**
 * In-memory implementation of the EvalRunRepository interface.
 */
@Repository
class InMemoryEvalRunRepository : EvalRunRepository {
    private val evalRuns = ConcurrentHashMap<String, EvalRun>()

    override fun createEvalRun(evalRun: EvalRun): EvalRun {
        val evalRunId = "run_${UUID.randomUUID().toString().replace("-", "")}"
        val newEvalRun = evalRun.copy(id = evalRunId)
        evalRuns[evalRunId] = newEvalRun
        return newEvalRun
    }

    override fun getEvalRun(evalRunId: String): EvalRun? {
        return evalRuns[evalRunId]
    }

    override fun listEvalRuns(): List<EvalRun> {
        return evalRuns.values.toList()
    }
    
    override fun listEvalRunsByEvalId(evalId: String): List<EvalRun> {
        return evalRuns.values.filter { it.evalId == evalId }
    }

    override fun updateEvalRun(evalRun: EvalRun): EvalRun {
        if (!evalRuns.containsKey(evalRun.id)) {
            throw IllegalArgumentException("Evaluation run not found with ID: ${evalRun.id}")
        }
        evalRuns[evalRun.id] = evalRun
        return evalRun
    }
    
    override fun deleteEvalRun(evalRunId: String): Boolean {
        return evalRuns.remove(evalRunId) != null
    }
} 