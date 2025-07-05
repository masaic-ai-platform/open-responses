package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.CompletionsRunDataSource
import ai.masaic.openevals.api.model.CreateEvalRunRequest
import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.EvalRunStatus
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.repository.EvalRunRepository
import ai.masaic.openevals.api.service.runner.EvalRunner
import ai.masaic.openevals.api.validation.EvalRunValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Service for managing evaluation runs.
 * Uses suspend functions for non-blocking operations.
 */
@Service
class EvalRunService(
    private val evalRunRepository: EvalRunRepository,
    private val evalRepository: EvalRepository,
    private val evalRunner: EvalRunner,
    private val evalRunValidator: EvalRunValidator,
) {
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    /**
     * Create a new evaluation run for a specific eval.
     *
     * @param evalId The ID of the evaluation to run
     * @param request The evaluation run creation request
     * @return The created evaluation run
     */
    suspend fun createEvalRun(
        headers: MultiValueMap<String, String>,
        evalId: String,
        request: CreateEvalRunRequest,
    ): EvalRun {
        // Verify that the eval exists
        val eval =
            evalRepository.getEval(evalId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found with ID: $evalId")
        
        // Validate the request
        try {
            evalRunValidator.validate(request)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        
        // Extract model from data source if it's a completions run
        val model =
            when (request.dataSource) {
                is CompletionsRunDataSource -> request.dataSource.model
                else -> null
            }

        // Create the evaluation run
        val evalRun =
            EvalRun(
                apiKey = extractApiKey(headers),
                id = "",
                evalId = evalId,
                name = request.name,
                createdAt = Instant.now().epochSecond,
                dataSource = request.dataSource,
                model = model,
                status = EvalRunStatus.QUEUED,
                metadata = request.metadata ?: emptyMap(),
            )

        val createdEvalRun = evalRunRepository.createEvalRun(evalRun)
        
        // Start the evaluation process asynchronously using Kotlin coroutines
        coroutineScope.launch {
            evalRunner.processEvalRun(createdEvalRun)
        }
        
        return createdEvalRun
    }

    /**
     * Get an evaluation run by ID.
     *
     * @param evalRunId The ID of the evaluation run to retrieve
     * @return The evaluation run, or null if not found
     */
    suspend fun getEvalRun(evalRunId: String): EvalRun? = evalRunRepository.getEvalRun(evalRunId)

    /**
     * List all evaluation runs for a specific eval.
     *
     * @param evalId The ID of the eval
     * @return A list of evaluation runs for the specified eval
     */
    suspend fun listEvalRunsByEvalId(evalId: String): List<EvalRun> {
        // Verify that the eval exists
        evalRepository.getEval(evalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found with ID: $evalId")
            
        return evalRunRepository.listEvalRunsByEvalId(evalId)
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
    suspend fun listEvalRunsByEvalId(
        evalId: String,
        after: String?,
        limit: Int,
        order: String,
        status: EvalRunStatus?,
    ): List<EvalRun> {
        // Verify that the eval exists
        evalRepository.getEval(evalId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found with ID: $evalId")
            
        return evalRunRepository.listEvalRunsByEvalId(evalId, after, limit, order, status)
    }

    /**
     * Delete an evaluation run.
     *
     * @param evalRunId The ID of the evaluation run to delete
     * @return True if the evaluation run was deleted, false otherwise
     */
    suspend fun deleteEvalRun(evalRunId: String): Boolean = evalRunRepository.deleteEvalRun(evalRunId)

    companion object {
        /**
         * Extract API key from headers.
         *
         * @param headerMap Map of HTTP headers
         * @return Extracted API key
         * @throws IllegalStateException if API key cannot be found
         */
        fun extractApiKey(headerMap: MultiValueMap<String, String>?): String {
            // Try to get from Authorization header
            val authHeader =
                headerMap
                    ?.get("Authorization")
                    ?.get(0)
                    ?.split(" ")
                    ?.getOrNull(1) ?: throw IllegalStateException("api-key is missing in request")
            return authHeader
        }
    }
} 
