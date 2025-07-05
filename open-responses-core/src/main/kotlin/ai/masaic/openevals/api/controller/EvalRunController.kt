package ai.masaic.openevals.api.controller

import ai.masaic.openevals.api.model.CreateEvalRunRequest
import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.EvalRunStatus
import ai.masaic.openevals.api.service.EvalRunService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for handling evaluation run API requests.
 * Uses coroutines with suspend functions for non-blocking operations.
 */
@RestController
@RequestMapping("/v1/evals")
class EvalRunController(
    private val evalRunService: EvalRunService,
) {
    /**
     * Create a new evaluation run.
     *
     * @param evalId The ID of the evaluation to create a run for
     * @param request The evaluation run creation request
     * @return The created evaluation run
     */
    @PostMapping("/{evalId}/runs")
    suspend fun createEvalRun(
        @RequestHeader headers: MultiValueMap<String, String>,
        @PathVariable evalId: String,
        @RequestBody request: CreateEvalRunRequest,
    ): ResponseEntity<EvalRun> {
        val evalRun = evalRunService.createEvalRun(headers, evalId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(evalRun)
    }

    /**
     * Get an evaluation run by ID.
     *
     * @param evalId The ID of the evaluation
     * @param runId The ID of the evaluation run to retrieve
     * @return The evaluation run
     */
    @GetMapping("/{evalId}/runs/{runId}")
    suspend fun getEvalRun(
        @PathVariable evalId: String,
        @PathVariable runId: String,
    ): ResponseEntity<EvalRun> {
        val evalRun =
            evalRunService.getEvalRun(runId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run not found with ID: $runId")
            
        // Verify that the run belongs to the specified eval
        if (evalRun.evalId != evalId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run with ID $runId does not belong to evaluation with ID $evalId")
        }
        
        return ResponseEntity.ok(evalRun)
    }

    /**
     * List evaluation runs for a specific eval.
     *
     * @param evalId The ID of the eval
     * @param after Identifier for the last run from the previous pagination request
     * @param limit Number of runs to retrieve (defaults to 20)
     * @param order Sort order for runs by timestamp (asc or desc, defaults to asc)
     * @param status Filter runs by status
     * @return A list of evaluation runs for the specified eval
     */
    @GetMapping("/{evalId}/runs")
    suspend fun listEvalRuns(
        @PathVariable evalId: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false, defaultValue = "asc") order: String,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<List<EvalRun>> {
        // Validate order parameter
        if (order != "asc" && order != "desc") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must be either 'asc' or 'desc'")
        }
        
        // Convert status string to EvalRunStatus enum if provided
        val statusEnum =
            status?.let {
                try {
                    EvalRunStatus.fromValue(it)
                } catch (e: IllegalArgumentException) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST, 
                        "Status must be one of: 'queued', 'in_progress', 'failed', 'completed', 'canceled'",
                    )
                }
            }
        
        val runs = evalRunService.listEvalRunsByEvalId(evalId, after, limit, order, statusEnum)
        return ResponseEntity.ok(runs)
    }

    /**
     * Delete an evaluation run.
     *
     * @param evalId The ID of the evaluation
     * @param runId The ID of the evaluation run to delete
     * @return No content response
     */
    @DeleteMapping("/{evalId}/runs/{runId}")
    suspend fun deleteEvalRun(
        @PathVariable evalId: String,
        @PathVariable runId: String,
    ): ResponseEntity<Void> {
        val evalRun =
            evalRunService.getEvalRun(runId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run not found with ID: $runId")
            
        // Verify that the run belongs to the specified eval
        if (evalRun.evalId != evalId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run with ID $runId does not belong to evaluation with ID $evalId")
        }
        
        val deleted = evalRunService.deleteEvalRun(runId)
        if (!deleted) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run not found with ID: $runId")
        }
        
        return ResponseEntity.noContent().build()
    }
} 
