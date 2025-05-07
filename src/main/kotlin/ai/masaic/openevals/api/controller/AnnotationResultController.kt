package ai.masaic.openevals.api.controller

import ai.masaic.openevals.api.model.AnnotationResult
import ai.masaic.openevals.api.model.AnnotatedBy
import ai.masaic.openevals.api.model.AnnotationAggregationResponse
import ai.masaic.openevals.api.model.UpdateAnnotationRequest
import ai.masaic.openevals.api.service.AnnotationResultService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for annotation results and aggregation endpoints.
 */
@CrossOrigin("*")
@RestController
@RequestMapping("/v1/evals")
class AnnotationResultController(
    private val annotationResultService: AnnotationResultService,
) {
    private val logger = LoggerFactory.getLogger(AnnotationResultController::class.java)

    /**
     * Get annotation results for a specific evaluation run and test criterion.
     *
     * @param evalId The evaluation ID
     * @param runId The run ID
     * @param testId The test/criterion ID
     * @param after Identifier for the last annotation result from the previous pagination request
     * @param limit Number of results to retrieve (defaults to 50)
     * @param order Sort order for results by creation timestamp (asc or desc, defaults to asc)
     * @return List of annotation results for the specific test
     * @throws ResponseStatusException if annotation results are not found or an error occurs
     */
    @GetMapping("/{eval_id}/runs/{run_id}/tests/{test_id}/annotations")
    suspend fun getAnnotationResultsByTest(
        @PathVariable("eval_id") evalId: String,
        @PathVariable("run_id") runId: String,
        @PathVariable("test_id") testId: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false, defaultValue = "asc") order: String,
    ): ResponseEntity<List<AnnotationResult>> {
        logger.info("Request for annotation results: eval=$evalId, run=$runId, test=$testId, after=$after, limit=$limit, order=$order")
        
        try {
            // Validate order parameter
            if (order != "asc" && order != "desc") {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must be either 'asc' or 'desc'")
            }
            
            val results = annotationResultService.getAnnotationResultsByTest(runId, testId, after, limit, order)
            
            if (results.isEmpty() && after == null) {
                throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No annotation results found for eval_id=$evalId, run_id=$runId, test_id=$testId",
                )
            }
            
            return ResponseEntity.ok(results)
        } catch (e: ResponseStatusException) {
            // Re-throw as is
            throw e
        } catch (e: Exception) {
            logger.error("Error getting annotation results", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error retrieving annotation results: ${e.message}",
            )
        }
    }

    /**
     * Get aggregated annotation results for a specific evaluation run and test criterion.
     *
     * @param evalId The evaluation ID
     * @param runId The run ID
     * @param testId The test/criterion ID
     * @return Aggregated annotation results
     * @throws ResponseStatusException if annotations are not found or an error occurs
     */
    @GetMapping("/{eval_id}/runs/{run_id}/tests/{test_id}/annotations/aggregation")
    suspend fun getAggregatedAnnotations(
        @PathVariable("eval_id") evalId: String,
        @PathVariable("run_id") runId: String,
        @PathVariable("test_id") testId: String,
    ): ResponseEntity<AnnotationAggregationResponse> {
        logger.info("Request for aggregated annotations: eval=$evalId, run=$runId, test=$testId")
        
        try {
            val response = annotationResultService.getAggregatedAnnotations(evalId, runId, testId)
            
            // If no annotations were found, return a 404 response
            if (response.annotationsCount == 0) {
                throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No annotations found for eval_id=$evalId, run_id=$runId, test_id=$testId",
                )
            }
            
            // If no aggregations were found, this could mean no level1/level2 attributes exist
            if (response.aggregations.isEmpty()) {
                logger.warn("No aggregations found for eval=$evalId, run=$runId, test=$testId, though annotations exist")
            }
            
            return ResponseEntity.ok(response)
        } catch (e: ResponseStatusException) {
            // Re-throw as is
            throw e
        } catch (e: Exception) {
            logger.error("Error getting aggregated annotations", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error processing annotation aggregation: ${e.message}",
            )
        }
    }
    
    /**
     * Update an annotation result.
     *
     * @param evalId The evaluation ID
     * @param runId The run ID
     * @param testId The test/criterion ID
     * @param annotationId The annotation ID to update
     * @param request The update request containing new values
     * @return The updated annotation result
     * @throws ResponseStatusException if annotation is not found or an error occurs
     */
    @PostMapping("/{eval_id}/runs/{run_id}/tests/{test_id}/annotations/{annotation_id}")
    suspend fun updateAnnotation(
        @PathVariable("eval_id") evalId: String,
        @PathVariable("run_id") runId: String,
        @PathVariable("test_id") testId: String,
        @PathVariable("annotation_id") annotationId: String,
        @RequestBody request: UpdateAnnotationRequest,
    ): ResponseEntity<AnnotationResult> {
        logger.info("Request to update annotation: eval=$evalId, run=$runId, test=$testId, annotation=$annotationId")
        
        try {
            // Hard code the lastAnnotatedBy value to HUMAN
            val updatedRequest = request.copy(lastAnnotatedBy = AnnotatedBy.HUMAN)
            
            val updatedAnnotation = annotationResultService.updateAnnotation(
                annotationId, 
                runId, 
                testId,
                updatedRequest.annotationAttributes,
                updatedRequest.lastAnnotatedBy ?: AnnotatedBy.HUMAN
            )
            
            return ResponseEntity.ok(updatedAnnotation)
        } catch (e: NoSuchElementException) {
            logger.error("Annotation not found", e)
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No annotation found with id=$annotationId for eval_id=$evalId, run_id=$runId, test_id=$testId",
            )
        } catch (e: ResponseStatusException) {
            // Re-throw as is
            throw e
        } catch (e: Exception) {
            logger.error("Error updating annotation", e)
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error updating annotation: ${e.message}",
            )
        }
    }
}
