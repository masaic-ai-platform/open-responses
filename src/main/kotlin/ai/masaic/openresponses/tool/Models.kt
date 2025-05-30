package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.Serializable

/**
 * Data class representing metadata about AI models.
 *
 * @property models List of AI model information
 */
@Serializable
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
@Serializable
data class AIModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val provider: String,
)

/**
 * Parameters for file search operation.
 */
@Serializable
data class FileSearchParams(
    val query: String,
)

/**
 * Response from file search operation.
 */
@Serializable
data class FileSearchResponse(
    val data: List<FileSearchResult>,
)

/**
 * Individual file search result.
 */
@Serializable
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
@Serializable
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
@Serializable
data class AgenticSearchParams(
    val query: String,
)

/**
 * Response from agentic search operation.
 */
data class AgenticSearchResponse(
    val data: List<AgenticSearchResult>,
    @JsonProperty("search_iterations")
    val search_iterations: List<AgenticSearchIteration>,
    @JsonProperty("knowledge_acquired")
    val knowledge_acquired: String = "",
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
    val annotations: List<FileCitation>,
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
    @JsonIgnore
    val results: MutableList<VectorStoreSearchResult> = mutableListOf(),
)

/**
 * Arguments for the image generation tool.
 * These parameters are used to request image creation from an external API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImageGenerationToolArguments(
    @JsonProperty("prompt")
    val prompt: String,
    @JsonProperty("previous_image_id")
    val previousImageId: String? = null,
    val isEdit: Boolean = previousImageId != null,
)
