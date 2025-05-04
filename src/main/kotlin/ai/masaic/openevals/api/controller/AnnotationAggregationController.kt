package ai.masaic.openevals.api.controller

import ai.masaic.openevals.api.model.AnnotationAggregationResponse
import ai.masaic.openevals.api.service.AnnotationAggregationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Controller for annotation aggregation endpoints.
 */
@RestController
@RequestMapping("/evals")
class AnnotationAggregationController(
    private val annotationAggregationService: AnnotationAggregationService
) {
    private val logger = LoggerFactory.getLogger(AnnotationAggregationController::class.java)

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
        @PathVariable("test_id") testId: String
    ): ResponseEntity<AnnotationAggregationResponse> {
        logger.info("Request for aggregated annotations: eval=$evalId, run=$runId, test=$testId")
        
        try {
            val response = annotationAggregationService.getAggregatedAnnotations(evalId, runId, testId)
            
            // If no annotations were found, return a 404 response
            if (response.annotationsCount == 0) {
                throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No annotations found for eval_id=$evalId, run_id=$runId, test_id=$testId"
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
                "Error processing annotation aggregation: ${e.message}"
            )
        }
    }
} 