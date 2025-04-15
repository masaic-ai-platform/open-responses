package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.CreateEvalRunRequest
import ai.masaic.openresponses.api.model.EvalRun
import ai.masaic.openresponses.api.service.EvalRunService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for handling evaluation run API requests.
 */
@RestController
@RequestMapping("/v1/evals")
class EvalRunController(private val evalRunService: EvalRunService) {
    
    /**
     * Create a new evaluation run.
     *
     * @param evalId The ID of the evaluation to create a run for
     * @param request The evaluation run creation request
     * @return The created evaluation run
     */
    @PostMapping("/{evalId}/runs")
    fun createEvalRun(
        @RequestHeader headers: MultiValueMap<String, String>,
        @PathVariable evalId: String,
        @RequestBody request: CreateEvalRunRequest
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
    fun getEvalRun(
        @PathVariable evalId: String,
        @PathVariable runId: String
    ): ResponseEntity<EvalRun> {
        val evalRun = evalRunService.getEvalRun(runId) ?: 
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run not found with ID: $runId")
            
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
     * @return A list of evaluation runs for the specified eval
     */
    @GetMapping("/{evalId}/runs")
    fun listEvalRuns(@PathVariable evalId: String): ResponseEntity<List<EvalRun>> {
        val runs = evalRunService.listEvalRunsByEvalId(evalId)
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
    fun deleteEvalRun(
        @PathVariable evalId: String,
        @PathVariable runId: String
    ): ResponseEntity<Void> {
        val evalRun = evalRunService.getEvalRun(runId) ?: 
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation run not found with ID: $runId")
            
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
