package ai.masaic.openevals.api.model

import ai.masaic.openevals.api.service.runner.CriterionEvaluator
import com.fasterxml.jackson.annotation.*
import java.time.Instant

/**
 * Main EvalRun object representing an evaluation run.
 */
data class EvalRun(
    @JsonIgnore
    val apiKey: String,
    val id: String,
    @JsonProperty("object")
    val objectType: String = "eval.run",
    @JsonProperty("eval_id")
    val evalId: String,
    val name: String?,
    @JsonProperty("created_at")
    val createdAt: Long = Instant.now().epochSecond,
    @JsonProperty("data_source")
    val dataSource: RunDataSource,
    val model: String?,
    val status: EvalRunStatus = EvalRunStatus.QUEUED,
    @JsonProperty("result_counts")
    val resultCounts: ResultCounts? = null,
    @JsonProperty("per_testing_criteria_results")
    val perTestingCriteriaResults: List<TestingCriteriaResult>? = null,
    @JsonProperty("per_model_usage")
    val perModelUsage: List<ModelUsage>? = null,
    @JsonProperty("report_url")
    val reportUrl: String? = "Coming soon with Masaic.AI dashboard",
    val metadata: Map<String, String>? = null,
    val error: EvalRunError? = null,
)

/**
 * Enum representing the status of an evaluation run.
 */
enum class EvalRunStatus {
    @JsonProperty("queued")
    QUEUED,
    
    @JsonProperty("in_progress")
    IN_PROGRESS,
    
    @JsonProperty("failed")
    FAILED,
    
    @JsonProperty("completed")
    COMPLETED,
    
    @JsonProperty("canceled")
    CANCELED,
    
    ;

    @JsonValue
    fun getValue(): String =
        when (this) {
            QUEUED -> "queued"
            IN_PROGRESS -> "in_progress"
            FAILED -> "failed"
            COMPLETED -> "completed"
            CANCELED -> "canceled"
        }

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromValue(value: String): EvalRunStatus =
            when (value) {
                "queued" -> QUEUED
                "in_progress" -> IN_PROGRESS
                "failed" -> FAILED
                "completed" -> COMPLETED
                "canceled" -> CANCELED
                else -> throw IllegalArgumentException("Unknown status: $value")
            }
    }
}

/**
 * Error details for an eval run.
 */
data class EvalRunError(
    val code: String,
    val message: String,
)

/**
 * Result counts for an eval run.
 */
data class ResultCounts(
    val passed: Int = 0,
    val failed: Int = 0,
    val errored: Int = 0,
    val total: Int = 0,
)

/**
 * Testing criteria results for an eval run.
 */
data class TestingCriteriaResult(
    @JsonProperty("testing_criteria")
    val testingCriteria: String,
    val criterionResults: List<CriterionEvaluator.CriterionResult>? = null,
    val passed: Int = 0,
    val failed: Int = 0,
)

/**
 * Model usage stats for an eval run.
 */
data class ModelUsage(
    @JsonProperty("model_name")
    val modelName: String,
    @JsonProperty("invocation_count")
    val invocationCount: Int = 0,
    @JsonProperty("prompt_tokens")
    val promptTokens: Int = 0,
    @JsonProperty("completion_tokens")
    val completionTokens: Int = 0,
    @JsonProperty("cached_tokens")
    val cachedTokens: Int = 0,
    @JsonProperty("total_tokens")
    val totalTokens: Int = 0,
)

// Run Data Source

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
//    JsonSubTypes.Type(value = JsonlRunDataSource::class, name = "jsonl"),
    JsonSubTypes.Type(value = CompletionsRunDataSource::class, name = "completions"),
)
@JsonIgnoreProperties(ignoreUnknown = true)
interface RunDataSource {
    val source: DataSource
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = FileDataSource::class, name = "file_id"),
//    JsonSubTypes.Type(value = CSVFileDataSource::class, name = "csv")
)
interface DataSource

data class FileDataSource(
    val id: String,
) : DataSource

// Completions Run Data Source
data class CompletionsRunDataSource(
    @JsonProperty("input_messages")
    val inputMessages: InputMessages,
    @JsonProperty("sampling_params")
    val samplingParams: SamplingParams? = null,
    val model: String,
    override val source: DataSource,
) : RunDataSource

// Input Messages
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TemplateInputMessages::class, name = "template"),
    JsonSubTypes.Type(value = ItemReferenceInputMessages::class, name = "item_reference"),
)
interface InputMessages

data class TemplateInputMessages(
    val type: String = "template",
    val template: List<ChatMessage>,
) : InputMessages

data class ItemReferenceInputMessages( // TODO: JB revisit ... can be used for stored completions
    val type: String = "item_reference",
    @JsonProperty("item_reference")
    val itemReference: String,
) : InputMessages

data class ChatMessage(
    val role: String,
    val content: String,
)

// Sampling Parameters
data class SamplingParams(
    val temperature: Double = 1.0,
    @JsonProperty("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @JsonProperty("top_p")
    val topP: Double = 1.0,
    @JsonProperty("frequency_penalty")
    val frequencyPenalty: Double = 0.0,
    @JsonProperty("presence_penalty")
    val presencePenalty: Double = 0.0,
    @JsonProperty("seed")
    val seed: Int? = null,
)

// Create Eval Run Request
data class CreateEvalRunRequest(
    val name: String? = null,
    @JsonProperty("data_source")
    val dataSource: RunDataSource,
    val metadata: Map<String, String>? = null,
)

// Completion results
data class CompletionResult(
    @JsonProperty("content_json")
    val contentJson: String,
    val error: String? = null,
) 
