package ai.masaic.openevals.api.model

/**
 * Response model for annotation aggregation.
 */
data class AnnotationAggregationResponse(
    val evalId: String,
    val runId: String,
    val testId: String,
    val annotationsCount: Int,
    val aggregations: List<Level1Aggregation>
)

/**
 * Level 1 aggregation information.
 */
data class Level1Aggregation(
    val name: String,
    val count: Int,
    val level2: List<Level2Aggregation>? = null
)

/**
 * Level 2 aggregation information.
 */
data class Level2Aggregation(
    val name: String,
    val count: Int
) 