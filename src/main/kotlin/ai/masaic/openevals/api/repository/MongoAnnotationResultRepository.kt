package ai.masaic.openevals.api.repository

import ai.masaic.openevals.api.model.AnnotationResult
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import javax.annotation.PostConstruct

/**
 * MongoDB implementation of AnnotationResultRepository.
 *
 * This implementation stores annotation results in MongoDB.
 * It uses reactive MongoDB with Kotlin coroutines for non-blocking operations.
 *
 * It is only enabled when open-responses.store.type=mongodb
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "mongodb")
class MongoAnnotationResultRepository(
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
) : AnnotationResultRepository {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val ANNOTATION_RESULT_COLLECTION = "annotation_results"
    }

    /**
     * Initialize indexes for the collection to ensure efficient querying.
     */
    @PostConstruct
    fun initializeIndexes() {
        try {
            // Create index on evalRunId and criterionId
            val evalRunIdIndex = Index().on("evalRunId", Sort.Direction.ASC)
            val criterionIdIndex = Index().on("criterionId", Sort.Direction.ASC)
            val combinedIndex = Index().on("evalRunId", Sort.Direction.ASC).on("criterionId", Sort.Direction.ASC)
            
            reactiveMongoTemplate.indexOps(ANNOTATION_RESULT_COLLECTION).ensureIndex(evalRunIdIndex).subscribe()
            reactiveMongoTemplate.indexOps(ANNOTATION_RESULT_COLLECTION).ensureIndex(criterionIdIndex).subscribe()
            reactiveMongoTemplate.indexOps(ANNOTATION_RESULT_COLLECTION).ensureIndex(combinedIndex).subscribe()
            
            logger.info { "Created indexes for annotation results collection" }
        } catch (e: Exception) {
            logger.error(e) { "Error creating indexes for annotation results collection" }
        }
    }

    /**
     * Save an annotation result.
     *
     * @param annotationResult The annotation result to save
     * @return The saved annotation result
     */
    override suspend fun saveAnnotationResult(annotationResult: AnnotationResult): AnnotationResult {
        try {
            return reactiveMongoTemplate.save(annotationResult, ANNOTATION_RESULT_COLLECTION).awaitFirst().also {
                logger.info { "Saved annotation result with ID: ${it.id} for eval run ${it.evalRunId}" }
            }
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

            val savedResults =
                Flux
                    .fromIterable(annotationResults)
                    .flatMap { reactiveMongoTemplate.save(it, ANNOTATION_RESULT_COLLECTION) }
                    .collectList()
                    .awaitSingle()

            logger.info { "Saved ${savedResults.size} annotation results" }
            return savedResults
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
            val query =
                Query(Criteria.where("evalRunId").`is`(evalRunId))
                    .with(Sort.by(Sort.Direction.ASC, "createdAt"))
            
            return reactiveMongoTemplate
                .find<AnnotationResult>(query, ANNOTATION_RESULT_COLLECTION)
                .collectList()
                .awaitSingle()
                .also {
                    logger.info { "Found ${it.size} annotation results for eval run ID: $evalRunId" }
                }
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
            val query =
                Query(
                    Criteria
                        .where("evalRunId")
                        .`is`(evalRunId)
                        .and("criterionId")
                        .`is`(criterionId),
                ).with(Sort.by(Sort.Direction.ASC, "createdAt"))
            
            return reactiveMongoTemplate
                .find<AnnotationResult>(query, ANNOTATION_RESULT_COLLECTION)
                .collectList()
                .awaitSingle()
                .also {
                    logger.info { "Found ${it.size} annotation results for eval run ID: $evalRunId and criterion ID: $criterionId" }
                }
        } catch (e: Exception) {
            logger.error(e) { "Error finding annotation results for eval run ID: $evalRunId and criterion ID: $criterionId" }
            return emptyList()
        }
    }

    /**
     * Find annotation results by evalRunId, criterionId, and a specific attribute value.
     *
     * @param evalRunId The evaluation run ID
     * @param criterionId The criterion ID
     * @param attributeName The name of the attribute to match
     * @param attributeValue The value of the attribute to match
     * @return List of annotation results matching the criteria
     */
    suspend fun findByEvalRunIdAndCriterionIdAndAttribute(
        evalRunId: String,
        criterionId: String,
        attributeName: String,
        attributeValue: Any,
    ): List<AnnotationResult> {
        try {
            val query =
                Query(
                    Criteria
                        .where("evalRunId")
                        .`is`(evalRunId)
                        .and("criterionId")
                        .`is`(criterionId)
                        .and("annotationAttributes.$attributeName")
                        .`is`(attributeValue),
                ).with(Sort.by(Sort.Direction.ASC, "createdAt"))

            return reactiveMongoTemplate
                .find<AnnotationResult>(query, ANNOTATION_RESULT_COLLECTION)
                .collectList()
                .awaitSingle()
                .also {
                    logger.info { "Found ${it.size} annotation results for eval run ID: $evalRunId, criterion ID: $criterionId, and attribute $attributeName=$attributeValue" }
                }
        } catch (e: Exception) {
            logger.error(e) { "Error finding annotation results for eval run ID: $evalRunId, criterion ID: $criterionId, and attribute $attributeName=$attributeValue" }
            return emptyList()
        }
    }
}
