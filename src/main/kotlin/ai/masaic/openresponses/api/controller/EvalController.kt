package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.CreateEvalRequest
import ai.masaic.openresponses.api.model.Eval
import ai.masaic.openresponses.api.model.EvalListResponse
import ai.masaic.openresponses.api.model.ListEvalsParams
import ai.masaic.openresponses.api.service.EvalService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for handling evaluation API requests.
 */
@RestController
@RequestMapping("/v1/evals")
class EvalController(private val evalService: EvalService) {
    
    /**
     * Create a new evaluation.
     *
     * @param request The evaluation creation request
     * @return The created evaluation
     */
    @PostMapping
    fun createEval(@RequestBody request: CreateEvalRequest): ResponseEntity<Eval> {
        val eval = evalService.createEval(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(eval)
    }
    
    /**
     * Get an evaluation by ID.
     *
     * @param evalId The ID of the evaluation to retrieve
     * @return The evaluation
     */
    @GetMapping("/{evalId}")
    fun getEval(@PathVariable evalId: String): ResponseEntity<Eval> {
        val eval = evalService.getEval(evalId) ?: 
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found with ID: $evalId")
        return ResponseEntity.ok(eval)
    }
    
    /**
     * List evaluations with optional pagination and filtering.
     *
     * @param limit Maximum number of evaluations to return
     * @param order Order of evaluations ("asc" or "desc" by creation time)
     * @param after Return evaluations after this evaluation ID
     * @param before Return evaluations before this evaluation ID
     * @param metadata Filter evaluations by metadata key-value pairs
     * @return A paginated list of evaluations that match the criteria
     */
    @GetMapping
    fun listEvals(
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false, defaultValue = "desc") order: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) metadata: Map<String, String>?
    ): ResponseEntity<EvalListResponse> {
        // Validate parameters
        if (limit < 1 || limit > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 100")
        }
        
        if (!order.equals("asc", ignoreCase = true) && !order.equals("desc", ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must be 'asc' or 'desc'")
        }
        
        val params = ListEvalsParams(
            limit = limit,
            order = order,
            after = after,
            before = before,
            metadata = metadata
        )
        
        val response = evalService.listEvals(params)
        return ResponseEntity.ok(response)
    }
    
    /**
     * Delete an evaluation.
     *
     * @param evalId The ID of the evaluation to delete
     * @return No content response
     */
    @DeleteMapping("/{evalId}")
    fun deleteEval(@PathVariable evalId: String): ResponseEntity<Void> {
        val deleted = evalService.deleteEval(evalId)
        if (!deleted) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Evaluation not found with ID: $evalId")
        }
        return ResponseEntity.noContent().build()
    }
} 