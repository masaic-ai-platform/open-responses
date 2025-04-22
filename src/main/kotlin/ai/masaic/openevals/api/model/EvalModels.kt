package ai.masaic.openevals.api.model

import com.fasterxml.jackson.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

// Main Eval object
data class Eval(
    val id: String,
    @JsonProperty("object")
    val objectType: String = "eval",
    val name: String?,
    @JsonProperty("created_at")
    val createdAt: Long = Instant.now().epochSecond,
    @JsonProperty("data_source_config")
    val dataSourceConfig: DataSourceConfig,
    @JsonProperty("testing_criteria")
    val testingCriteria: List<TestingCriterion>,
    val metadata: Map<String, String>? = null, // TODO: JB to revisit for constraints of 16 key value pair... etc
)

// Data Source Config
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = CustomDataSourceConfigRequest::class, name = "custom"),
//    JsonSubTypes.Type(value = CustomDataSourceConfig::class, name = "custom"),
    JsonSubTypes.Type(value = StoredCompletionsDataSourceConfig::class, name = "stored_completions"),
)
@JsonIgnoreProperties(ignoreUnknown = true)
interface DataSourceConfig

// Custom Data Source Config
data class CustomDataSourceConfigRequest(
    @JsonProperty("item_schema")
    val schema: Map<String, Any>,
    // TODO: JB to revisit
    @JsonProperty("include_sample_schema")
    val includeSampleSchema: Boolean = false,
) : DataSourceConfig

@JsonTypeName("custom")
data class CustomDataSourceConfig(
    val schema: Map<String, com.openai.core.JsonValue>,
) : DataSourceConfig

// Stored Completions Data Source Config
data class StoredCompletionsDataSourceConfig(
    val metadata: Map<String, String>,
) : DataSourceConfig

// Testing Criterion
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = LabelModelGrader::class, name = "label_model"),
    JsonSubTypes.Type(value = StringCheckGrader::class, name = "string_check"),
    JsonSubTypes.Type(value = TextSimilarityGrader::class, name = "text_similarity"),
)
@JsonIgnoreProperties(ignoreUnknown = true)
interface TestingCriterion {
    val name: String
    val id: String
}

// Label Model Grader
data class LabelModelGrader(
    override val name: String,
    override val id: String = "", // Default empty to be filled by service
    val model: String,
    @field:NotEmpty @field:Size(min = 1, message = "at least one input object with role, content must be provided")
    @field:Valid
    val input: List<SimpleInputMessage>,
    @field:NotEmpty @field:Size(min = 1)
    @field:Valid
    val labels: List<String>,
    @field:NotEmpty @field:Size(min = 1)
    @field:Valid
    @JsonProperty("passing_labels")
    val passingLabels: List<String>,
    @JsonIgnore
    val apiKey: String = "",
) : TestingCriterion

// String Check Grader
data class StringCheckGrader(
    override val name: String,
    override val id: String = "", // Default empty to be filled by service
    val input: String,
    val reference: String,
    val operation: Operation,
) : TestingCriterion {
    /**
     * The string check operation to perform.
     */
    enum class Operation {
        /**
         * Equal comparison (case-sensitive)
         */
        @JsonProperty("eq")
        EQUAL,

        /**
         * Not equal comparison (case-sensitive)
         */
        @JsonProperty("ne")
        NOT_EQUAL,

        /**
         * Like comparison (case-sensitive pattern matching)
         */
        @JsonProperty("like")
        LIKE,

        /**
         * Case-insensitive like comparison
         */
        @JsonProperty("ilike")
        ILIKE,

        ;

        @JsonValue
        fun getValue(): String =
            when (this) {
                EQUAL -> "eq"
                NOT_EQUAL -> "ne"
                LIKE -> "like"
                ILIKE -> "ilike"
            }

        companion object {
            @JsonCreator
            @JvmStatic
            fun fromValue(value: String): Operation =
                when (value.lowercase()) {
                    "eq" -> EQUAL
                    "ne" -> NOT_EQUAL
                    "like" -> LIKE
                    "ilike" -> ILIKE
                    else -> throw IllegalArgumentException("Unknown operation: $value. Allowed values are: eq, ne, like, ilike")
                }
        }
    }
}

// Text Similarity Grader
data class TextSimilarityGrader(
    override val name: String,
    override val id: String = "", // Default empty to be filled by service
    val input: String,
    val reference: String,
    @JsonProperty("evaluation_metric")
    val evaluationMetric: String,
    @JsonProperty("pass_threshold")
    val passThreshold: Double,
) : TestingCriterion

data class SimpleInputMessage(
    val role: String,
    val content: String,
)

// Request classes
data class CreateEvalRequest(
    val name: String?,
    @JsonProperty("data_source_config")
    val dataSourceConfig: DataSourceConfig,
    @field:NotEmpty @field:Size(min = 1) // at least one testing criterion
    @field:Valid
    @JsonProperty("testing_criteria")
    val testingCriteria: List<TestingCriterion>,
    val metadata: Map<String, String>? = null, // TODO: JB to revisit for constraints of 16 key value pair... etc
)

// Response classes for pagination
data class EvalListResponse(
    @JsonProperty("object")
    val objectType: String = "list",
    val data: List<Eval>,
    @JsonProperty("has_more")
    val hasMore: Boolean,
    @JsonProperty("first_id")
    val firstId: String?,
    @JsonProperty("last_id")
    val lastId: String?,
    val limit: Int,
)

// Parameters for listing evaluations
data class ListEvalsParams(
    val limit: Int = 20,
    val order: String = "desc", // "asc" or "desc"
    @JsonProperty("after")
    val after: String? = null,
    @JsonProperty("before")
    val before: String? = null,
    val metadata: Map<String, String>? = null,
)

// Request class for updating an eval
data class UpdateEvalRequest(
    val name: String? = null,
    val metadata: Map<String, String>? = null,
) 
