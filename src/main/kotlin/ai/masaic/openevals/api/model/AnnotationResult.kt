package ai.masaic.openevals.api.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Enum representing who annotated the result.
 */
enum class AnnotatedBy {
    MODEL, // Annotation was created by a model (default)
    HUMAN, // Annotation was updated by a human
    UNKNOWN, // Source of annotation is unknown
}

/**
 * Request model for updating annotation attributes.
 *
 * @property lastAnnotatedBy Who last annotated this result (MODEL, HUMAN, UNKNOWN)
 * @property annotationAttributes Map of attribute key-value pairs to update
 */
data class UpdateAnnotationRequest(
    val lastAnnotatedBy: AnnotatedBy? = null,
    val annotationAttributes: Map<String, Any> = emptyMap(),
)

/**
 * Response model for annotation aggregation.
 */
data class AnnotationAggregationResponse(
    val evalId: String,
    val runId: String,
    val testId: String,
    val annotationsCount: Int,
    val aggregations: List<Level1Aggregation>,
)

/**
 * Level 1 aggregation information.
 */
data class Level1Aggregation(
    val name: String,
    val count: Int,
    val level2: List<Level2Aggregation>? = null,
)

/**
 * Level 2 aggregation information.
 */
data class Level2Aggregation(
    val name: String,
    val count: Int,
)

/**
 * Model for storing annotation results in the database.
 */
@Document(collection = "annotation_results")
data class AnnotationResult(
    @Id
    val id: String = "annot_${java.util.UUID.randomUUID().toString().replace("-", "")}",
    val evalRunId: String,
    val criterionId: String,
    val annotationAttributes: Map<String, Any> = emptyMap(),
    val overriddenAnnotationAttributes: Map<String, Any> = emptyMap(),
    val lastAnnotatedBy: AnnotatedBy = AnnotatedBy.MODEL,
    val createdAt: Long = Instant.now().epochSecond,
    val updatedAt: Long = Instant.now().epochSecond,
) {
    companion object {
        private val objectMapper = ObjectMapper()

        /**
         * Create an AnnotationResult from a JSON string.
         * This is a convenience method for converting message strings to annotation attributes.
         *
         * @param evalRunId The evaluation run ID
         * @param criterionId The criterion ID
         * @param jsonString The JSON string to parse
         * @return An AnnotationResult with attributes parsed from the JSON string
         */
        fun fromJsonString(
            evalRunId: String,
            criterionId: String,
            jsonString: String,
        ): AnnotationResult =
            try {
                val attributes = objectMapper.readValue(jsonString, Map::class.java) as Map<String, Any>
                AnnotationResult(
                    evalRunId = evalRunId,
                    criterionId = criterionId,
                    annotationAttributes = attributes,
                )
            } catch (e: Exception) {
                // If parsing fails, store the original string as a single attribute
                AnnotationResult(
                    evalRunId = evalRunId, 
                    criterionId = criterionId,
                    annotationAttributes = mapOf("raw_content" to jsonString),
                )
            }
    }
} 
