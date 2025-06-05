package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.AnnotatedBy
import ai.masaic.openevals.api.model.AnnotationAggregationResponse
import ai.masaic.openevals.api.model.AnnotationResult
import ai.masaic.openevals.api.model.Level1Aggregation
import ai.masaic.openevals.api.model.Level2Aggregation
import ai.masaic.openevals.api.repository.AnnotationResultRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for retrieving and aggregating annotation results.
 */
@Service
class AnnotationResultService(
    private val annotationResultRepository: AnnotationResultRepository,
) {
    private val logger = LoggerFactory.getLogger(AnnotationResultService::class.java)
    
    // The attribute names used for level 1 and level 2 aggregations
    private val level1AttributeName = "handover_reason_l1"
    private val level2AttributeName = "handover_reason_l2"

    /**
     * Get annotation results for a specific evaluation run and test criterion with cursor-based pagination.
     *
     * @param runId The run ID
     * @param testId The test/criterion ID
     * @param after Cursor for the last annotation result from the previous pagination request (result id)
     * @param limit Number of results to retrieve
     * @param order Sort order (asc or desc)
     * @return List of annotation results for the specified evaluation run and test
     */
    suspend fun getAnnotationResultsByTest(
        runId: String,
        testId: String,
        after: String? = null,
        limit: Int = 50,
        order: String = "asc",
    ): List<AnnotationResult> {
        logger.info("Getting annotation results for run=$runId, test=$testId, after=$after, limit=$limit, order=$order")
        
        // Validate input parameters
        require(runId.isNotBlank()) { "runId cannot be blank" }
        require(testId.isNotBlank()) { "testId cannot be blank" }
        require(limit > 0) { "limit must be positive" }
        
        // Fetch all annotations for this run and test
        val allResults = annotationResultRepository.findByEvalRunIdAndCriterionId(runId, testId)
        
        // Sort based on order parameter (asc or desc by creation time)
        val sortedResults =
            when (order) {
                "asc" -> allResults.sortedBy { it.createdAt }
                "desc" -> allResults.sortedByDescending { it.createdAt }
                else -> allResults.sortedBy { it.createdAt } // Default to ascending
            }
        
        // Apply cursor-based pagination
        return applyCursorPagination(sortedResults, after, limit)
    }

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
     * Update an annotation with new attribute values.
     *
     * @param annotationId The ID of the annotation to update
     * @param runId The run ID for validation
     * @param testId The test/criterion ID for validation
     * @param newAttributes Map of new attribute values to apply
     * @param lastAnnotatedBy Who last annotated this result
     * @return The updated annotation result
     * @throws NoSuchElementException if the annotation is not found
     */
    suspend fun updateAnnotation(
        annotationId: String,
        runId: String,
        testId: String,
        newAttributes: Map<String, Any>,
        lastAnnotatedBy: AnnotatedBy = AnnotatedBy.HUMAN,
    ): AnnotationResult {
        logger.info("Updating annotation $annotationId with ${newAttributes.size} new attributes")
        
        // Fetch the existing annotation
        val existingAnnotation = findAnnotation(annotationId, runId, testId)
        
        // Process attributes to be updated
        val (updatedAttributes, retiredAttributes) =
            processAttributes(
                existingAnnotation.annotationAttributes,
                existingAnnotation.overriddenAnnotationAttributes,
                newAttributes,
            )
        
        // Create the updated annotation
        val updatedAnnotation =
            existingAnnotation.copy(
                annotationAttributes = updatedAttributes,
                overriddenAnnotationAttributes = retiredAttributes,
                lastAnnotatedBy = lastAnnotatedBy,
                updatedAt = Instant.now().epochSecond,
            )
        
        // Save and return the updated annotation
        return annotationResultRepository.saveAnnotationResult(updatedAnnotation)
    }

    /**
     * Find an annotation by ID and validate it belongs to the specified run and test.
     *
     * @param annotationId The annotation ID
     * @param runId The run ID for validation
     * @param testId The test/criterion ID for validation
     * @return The found annotation
     * @throws NoSuchElementException if the annotation is not found or doesn't match the run/test
     */
    private suspend fun findAnnotation(
        annotationId: String,
        runId: String,
        testId: String,
    ): AnnotationResult {
        // Find all annotations for this run and test
        val annotations = annotationResultRepository.findByEvalRunIdAndCriterionId(runId, testId)
        
        // Find the specific annotation by ID
        val annotation =
            annotations.find { it.id == annotationId }
                ?: throw NoSuchElementException("Annotation not found with ID: $annotationId")
        
        // Validate that it matches the run and test IDs
        if (annotation.evalRunId != runId || annotation.criterionId != testId) {
            throw NoSuchElementException(
                "Annotation with ID $annotationId does not belong to run $runId and test $testId",
            )
        }
        
        return annotation
    }

    /**
     * Process attributes for updating, creating retired versions of overridden values.
     * Only specific keys (handover_reason_l1, handover_reason_l2) are stored in retired attributes.
     *
     * @param existingAttributes Current annotation attributes
     * @param existingRetiredAttributes Current retired annotation attributes
     * @param newAttributes New attributes to apply
     * @return Pair of updated attributes and retired attributes maps
     */
    private fun processAttributes(
        existingAttributes: Map<String, Any>,
        existingRetiredAttributes: Map<String, Any>,
        newAttributes: Map<String, Any>,
    ): Pair<Map<String, Any>, Map<String, Any>> {
        if (newAttributes.isEmpty()) {
            return Pair(existingAttributes, existingRetiredAttributes)
        }
        
        // Start with the existing retired attributes
        val overriddenAttributesBuilder = existingRetiredAttributes.toMutableMap()
        
        // Apply new attributes, moving overridden values to retired
        val updatedAttributes = existingAttributes.toMutableMap()

        newAttributes.forEach { (key, value) ->
            // If the key exists in the current attributes and is one we want to track,
            // move its value to retired attributes without timestamp
            if (updatedAttributes[key] != overriddenAttributesBuilder[key]) {
                val oldValue = updatedAttributes[key]
                if (oldValue != null) {
                    // Add the old value to retired attributes without timestamp
                    overriddenAttributesBuilder[key] = oldValue
                }
            }
            // Set the new value
            updatedAttributes[key] = value
        }
        
        return Pair(updatedAttributes, overriddenAttributesBuilder)
    }

    /**
     * Apply cursor-based pagination to a list of results.
     *
     * @param results Sorted list of results
     * @param after Cursor for pagination (the ID of the last item from previous page)
     * @param limit Maximum number of items to return
     * @return Paginated list of results
     */
    private fun applyCursorPagination(
        results: List<AnnotationResult>,
        after: String?,
        limit: Int,
    ): List<AnnotationResult> {
        if (results.isEmpty()) {
            return emptyList()
        }
        
        // If no cursor is provided, return the first page
        if (after == null) {
            return results.take(limit)
        }
        
        // Find the index of the item after the cursor
        val afterIndex = results.indexOfFirst { it.id == after }
        
        // If we didn't find the cursor, return the first page
        if (afterIndex == -1) {
            logger.warn("Cursor $after not found in results, returning first page")
            return results.take(limit)
        }
        
        // Return items after the cursor, up to the limit
        val nextIndex = afterIndex + 1
        if (nextIndex >= results.size) {
            return emptyList()
        }
        
        return results.subList(nextIndex, results.size).take(limit)
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
            val level1Value = annotation.annotationAttributes[level1AttributeName]?.toString() ?: "UNKNOWN"
            val level2Value = annotation.annotationAttributes[level2AttributeName]?.toString() ?: "UNKNOWN"

            val level2Counts = level1Aggregations.getOrPut(level1Value) { mutableMapOf() }
            level2Counts[level2Value] = level2Counts.getOrDefault(level2Value, 0) + 1
        }

        // Remove all UNKNOWN level-2 entries from each bucket
        level1Aggregations.values.forEach { it.remove("UNKNOWN") }
        // Remove the UNKNOWN level-1 bucket entirely
        level1Aggregations.remove("UNKNOWN")

        // If after filtering there's nothing left, return empty
        if (level1Aggregations.isEmpty()) {
            return emptyList()
        }

        // Build and sort the final list
        return level1Aggregations
            .map { (level1Value, level2Counts) ->
                Level1Aggregation(
                    name = level1Value,
                    count = level2Counts.values.sum(),
                    level2 = createLevel2Aggregations(level2Counts),
                )
            }.sortedByDescending { it.count }
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
