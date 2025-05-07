package ai.masaic.openevals.api.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

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
    val createdAt: Long = Instant.now().epochSecond,
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
