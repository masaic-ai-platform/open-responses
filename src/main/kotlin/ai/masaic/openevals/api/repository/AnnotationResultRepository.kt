package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.AnnotationResult

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
    suspend fun findByEvalRunIdAndCriterionId(evalRunId: String, criterionId: String): List<AnnotationResult>
}
