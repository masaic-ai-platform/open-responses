package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.AnnotationAggregationResponse
import ai.masaic.openevals.api.model.AnnotationResult
import ai.masaic.openevals.api.model.Level1Aggregation
import ai.masaic.openevals.api.model.Level2Aggregation
import ai.masaic.openevals.api.repository.AnnotationResultRepository
import org.slf4j.LoggerFactory

/**
 * Service for aggregating annotation results.
 */
// @Service
class AnnotationAggregationService(
    private val annotationResultRepository: AnnotationResultRepository,
) {
    private val logger = LoggerFactory.getLogger(AnnotationAggregationService::class.java)
    
    // The attribute names used for level 1 and level 2 aggregations
    private val level1AttributeName = "handover_reason_l1"
    private val level2AttributeName = "handover_reason_l2"

    /**
     * Get aggregated annotation results for a specific evaluation run and test criterion.
     *
     * @param evalId The evaluation ID
     * @param runId The run ID
     * @param testId The test/criterion ID
     * @return Aggregated annotation results
     */
    suspend fun getAggregatedAnnotations(
        evalId: String,
        runId: String,
        testId: String,
    ): AnnotationAggregationResponse {
        logger.info("Getting aggregated annotations for eval=$evalId, run=$runId, test=$testId")
        
        // Validate input parameters
        require(evalId.isNotBlank()) { "evalId cannot be blank" }
        require(runId.isNotBlank()) { "runId cannot be blank" }
        require(testId.isNotBlank()) { "testId cannot be blank" }
        
        // Fetch all annotations for this run and test
        val annotations = annotationResultRepository.findByEvalRunIdAndCriterionId(runId, testId)
        logger.info("Found ${annotations.size} annotations for analysis")
        
        // Create the aggregation response
        return AnnotationAggregationResponse(
            evalId = evalId,
            runId = runId,
            testId = testId,
            annotationsCount = annotations.size,
            aggregations = createLevel1Aggregations(annotations),
        )
    }

    /**
     * Create level 1 aggregations from annotation results.
     *
     * @param annotations The list of annotation results
     * @return A list of level 1 aggregations
     */
    private fun createLevel1Aggregations(
        annotations: List<AnnotationResult>,
    ): List<Level1Aggregation> {
        if (annotations.isEmpty()) {
            return emptyList()
        }
        
        // Map to track level1 -> level2 counts
        val level1Aggregations = mutableMapOf<String, MutableMap<String, Int>>()
        
        // Process each annotation
        annotations.forEach { annotation ->
            // Extract level1 value, default to "UNKNOWN" if not present
            val level1Value = annotation.annotationAttributes[level1AttributeName]?.toString() ?: "UNKNOWN"
            
            // Extract level2 value, default to "UNKNOWN" if not present
            val level2Value = annotation.annotationAttributes[level2AttributeName]?.toString() ?: "UNKNOWN"
            
            // Update the level1 aggregation map
            val level2Counts = level1Aggregations.getOrPut(level1Value) { mutableMapOf() }
            level2Counts[level2Value] = level2Counts.getOrDefault(level2Value, 0) + 1
        }
        
        // Convert the map to a list of Level1Aggregation objects
        return level1Aggregations
            .map { (level1Value, level2Counts) ->
                val level1Count = level2Counts.values.sum()
            
                Level1Aggregation(
                    name = level1Value,
                    count = level1Count,
                    level2 = createLevel2Aggregations(level2Counts),
                )
            }.sortedByDescending { it.count } // Sort by count (highest first)
    }

    /**
     * Create level 2 aggregations from a map of level 2 values to counts.
     *
     * @param level2Counts Map of level 2 values to their counts
     * @return A list of level 2 aggregations
     */
    private fun createLevel2Aggregations(
        level2Counts: Map<String, Int>,
    ): List<Level2Aggregation>? {
        if (level2Counts.isEmpty()) {
            return null
        }
        
        // Convert the map to a list of Level2Aggregation objects
        return level2Counts
            .map { (level2Value, count) ->
                Level2Aggregation(
                    name = level2Value,
                    count = count,
                )
            }.sortedByDescending { it.count } // Sort by count (highest first)
    }

    /**
     * Sample method to demonstrate how to process the provided test data.
     * This method shows how the sample data would be processed.
     */
    fun processSampleData(sampleData: List<AnnotationResult>): AnnotationAggregationResponse =
        AnnotationAggregationResponse(
            evalId = "sample",
            runId = sampleData.firstOrNull()?.evalRunId ?: "unknown",
            testId = sampleData.firstOrNull()?.criterionId ?: "unknown",
            annotationsCount = sampleData.size,
            aggregations = createLevel1Aggregations(sampleData),
        )
} 
