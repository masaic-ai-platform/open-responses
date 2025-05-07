package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.AnnotationResult
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository interface for annotation results.
 */
interface AnnotationResultRepository {
    /**
     * Save an annotation result.
     *
     * @param annotationResult The annotation result to save
     * @return The saved annotation result
     */
    suspend fun saveAnnotationResult(annotationResult: AnnotationResult): AnnotationResult

    /**
     * Save multiple annotation results in a batch.
     *
     * @param annotationResults The list of annotation results to save
     * @return The list of saved annotation results
     */
    suspend fun saveAnnotationResults(annotationResults: List<AnnotationResult>): List<AnnotationResult>

    /**
     * Find annotation results by evalRunId.
     *
     * @param evalRunId The evaluation run ID
     * @return List of annotation results for the specified evaluation run
     */
    suspend fun findByEvalRunId(evalRunId: String): List<AnnotationResult>

    /**
     * Find annotation results by evalRunId and criterionId.
     *
     * @param evalRunId The evaluation run ID
     * @param criterionId The criterion ID
     * @return List of annotation results for the specified evaluation run and criterion
     */
    suspend fun findByEvalRunIdAndCriterionId(
        evalRunId: String,
        criterionId: String,
    ): List<AnnotationResult>
}

/**
 * In-memory implementation of AnnotationResultRepository.
 *
 * This implementation stores annotation results in memory.
 * It is used for development and testing purposes.
 *
 * It is only enabled when open-responses.store.type=memory
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryAnnotationResultRepository : AnnotationResultRepository {
    private val logger = KotlinLogging.logger {}

    // Use ConcurrentHashMap for thread safety
    private val annotationResults = ConcurrentHashMap<String, AnnotationResult>()

    /**
     * Save an annotation result.
     *
     * @param annotationResult The annotation result to save
     * @return The saved annotation result
     */
    override suspend fun saveAnnotationResult(annotationResult: AnnotationResult): AnnotationResult {
        try {
            annotationResults[annotationResult.id] = annotationResult
            logger.info { "Saved annotation result with ID: ${annotationResult.id} for eval run ${annotationResult.evalRunId}" }
            return annotationResult
        } catch (e: Exception) {
            logger.error(e) { "Error saving annotation result" }
            throw e
        }
    }

    /**
     * Save multiple annotation results in a batch.
     *
     * @param annotationResults The list of annotation results to save
     * @return The list of saved annotation results
     */
    override suspend fun saveAnnotationResults(annotationResults: List<AnnotationResult>): List<AnnotationResult> {
        try {
            if (annotationResults.isEmpty()) {
                logger.info { "No annotation results to save" }
                return emptyList()
            }

            annotationResults.forEach { result ->
                this.annotationResults[result.id] = result
            }

            logger.info { "Saved ${annotationResults.size} annotation results" }
            return annotationResults
        } catch (e: Exception) {
            logger.error(e) { "Error saving annotation results batch" }
            throw e
        }
    }

    /**
     * Find annotation results by evalRunId.
     *
     * @param evalRunId The evaluation run ID
     * @return List of annotation results for the specified evaluation run
     */
    override suspend fun findByEvalRunId(evalRunId: String): List<AnnotationResult> {
        try {
            val results =
                annotationResults.values
                    .filter { it.evalRunId == evalRunId }
                    .sortedBy { it.createdAt }
                    .toList()

            logger.info { "Found ${results.size} annotation results for eval run ID: $evalRunId" }
            return results
        } catch (e: Exception) {
            logger.error(e) { "Error finding annotation results for eval run ID: $evalRunId" }
            return emptyList()
        }
    }

    /**
     * Find annotation results by evalRunId and criterionId.
     *
     * @param evalRunId The evaluation run ID
     * @param criterionId The criterion ID
     * @return List of annotation results for the specified evaluation run and criterion
     */
    override suspend fun findByEvalRunIdAndCriterionId(
        evalRunId: String,
        criterionId: String,
    ): List<AnnotationResult> {
        try {
            val results =
                annotationResults.values
                    .filter { it.evalRunId == evalRunId && it.criterionId == criterionId }
                    .sortedBy { it.createdAt }
                    .toList()

            logger.info { "Found ${results.size} annotation results for eval run ID: $evalRunId and criterion ID: $criterionId" }
            return results
        } catch (e: Exception) {
            logger.error(e) { "Error finding annotation results for eval run ID: $evalRunId and criterion ID: $criterionId" }
            return emptyList()
        }
    }
}
