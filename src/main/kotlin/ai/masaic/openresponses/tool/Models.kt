package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * Data class representing metadata about a tool.
 *
 * @property id Unique identifier for the tool
 * @property name Human-readable name of the tool
 * @property description Detailed description of what the tool does
 */
data class ToolMetadata(
    val id: String,
    val name: String,
    val description: String,
)

/**
 * Data class representing metadata about AI models.
 *
 * @property models List of AI model information
 */
data class AIModelsMetadata(
    val models: List<AIModelInfo>,
)

/**
 * Data class representing metadata about an AI model.
 *
 * @property id Unique identifier for the model
 * @property name Human-readable name of the model
 * @property description Detailed description of what the model does
 * @property provider Name of the provider of the model
 */
data class AIModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val provider: String,
)

/**
 * Parameters for file search operation.
 */
data class FileSearchParams(
    val query: String,
)

/**
 * Response from file search operation.
 */
data class FileSearchResponse(
    val data: List<FileSearchResult>,
)

/**
 * Individual file search result.
 */
data class FileSearchResult(
    @JsonProperty("file_id")
    val file_id: String,
    val filename: String,
    val score: Double,
    val content: String,
    val annotations: List<FileCitation>,
)

/**
 * File citation annotation.
 */
data class FileCitation(
    val type: String,
    val index: Int,
    @JsonProperty("file_id")
    val file_id: String,
    val filename: String,
)

/**
 * Parameters for agentic search operation.
 */
data class AgenticSearchParams(
    val question: String,
    val max_iterations: Int = 5,
    val confidence_threshold: Float = 0.8f
)

/**
 * Response from agentic search operation.
 */
data class AgenticSearchResponse(
    val data: List<AgenticSearchResult>,
    @JsonProperty("search_iterations")
    val search_iterations: List<AgenticSearchIteration>,
    @JsonProperty("knowledge_acquired")
    val knowledge_acquired: String? = null
)

/**
 * Individual agentic search result.
 */
data class AgenticSearchResult(
    @JsonProperty("file_id")
    val file_id: String,
    val filename: String,
    val score: Double,
    val content: String,
    val annotations: List<FileCitation>
)

/**
 * Represents a single iteration in the agentic search process.
 */
data class AgenticSearchIteration(
    val query: String,
    @JsonProperty("is_final")
    var is_final: Boolean,
    @JsonProperty("applied_filters")
    val applied_filters: Map<String, Any>? = null,
    @JsonProperty("termination_reason")
    var termination_reason: String? = null,
    @JsonIgnore  // Don't include in the final response JSON
    val results: MutableList<VectorStoreSearchResult> = mutableListOf()
)
