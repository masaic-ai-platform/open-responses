package ai.masaic.openresponses.api.model

import ai.masaic.platform.api.config.ModelSettings
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseOutputText
import com.openai.models.responses.ResponseTextConfig
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Represents the reasoning information for a response.
 *
 * @property effort Description of the effort involved in generating the response
 * @property summary Summary of the reasoning process
 */
data class Reasoning(
    val effort: String? = null,
    val summary: String? = null,
)

/**
 * Tool implementation for file search operations.
 *
 * @property type The type identifier for this tool, should be "file_search"
 * @property filters Optional filters to apply to the search
 * @property maxNumResults Maximum number of results to return
 * @property rankingOptions Options for ranking search results
 * @property vectorStoreIds List of vector store IDs to search in
 */
data class FileSearchTool(
    override val type: String,
    val filters: Any? = null,
    @JsonProperty("max_num_results")
    val maxNumResults: Int = 20,
    @JsonProperty("ranking_options")
    val rankingOptions: RankingOptions? = null,
    @JsonProperty("vector_store_ids")
    val vectorStoreIds: List<String>? = null,
    val alias: String? = type,
    val modelInfo: ModelInfo?,
) : Tool

/**
 * Tool implementation for agentic search operations.
 *
 * @property type The type identifier for this tool, should be "agentic_search"
 * @property filters Optional filters to apply to the search
 * @property maxNumResults Maximum number of results to return
 * @property vectorStoreIds List of vector store IDs to search in
 * @property maxIterations Maximum number of search iterations
 * @property alias Optional alias for the tool
 * @property enablePresencePenaltyTuning Optional flag to enable tuning
 * @property enableFrequencyPenaltyTuning Optional flag to enable tuning
 * @property enableTemperatureTuning Optional flag to enable tuning
 * @property enableTopPTuning Optional flag to enable tuning
 */
data class AgenticSeachTool(
    override val type: String,
    val filters: Any? = null,
    @JsonProperty("max_num_results")
    val maxNumResults: Int = 20,
    @JsonProperty("vector_store_ids")
    val vectorStoreIds: List<String>? = null,
    @JsonProperty("max_iterations")
    val maxIterations: Int = 5,
    val alias: String? = type,
    @JsonProperty("enable_presence_penalty_tuning")
    val enablePresencePenaltyTuning: Boolean? = null,
    @JsonProperty("enable_frequency_penalty_tuning")
    val enableFrequencyPenaltyTuning: Boolean? = null,
    @JsonProperty("enable_temperature_tuning")
    val enableTemperatureTuning: Boolean? = null,
    @JsonProperty("enable_top_p_tuning")
    val enableTopPTuning: Boolean? = null,
    val modelInfo: ModelInfo?,
) : Tool

/**
 * Configuration for ranking search results.
 *
 * @property ranker The ranking algorithm to use
 * @property scoreThreshold Minimum score threshold for including results
 */
data class RankingOptions(
    val ranker: String = "auto",
    @JsonProperty("score_threshold")
    val scoreThreshold: Double = 0.0,
)

/**
 * Interface representing a tool that can be used in API requests.
 *
 * All tool implementations must specify their type.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
    defaultImpl = MasaicManagedTool::class,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = WebSearchTool::class, name = "web_search_preview"),
    JsonSubTypes.Type(value = FileSearchTool::class, name = "file_search"),
    JsonSubTypes.Type(value = FunctionTool::class, name = "function"),
    JsonSubTypes.Type(value = AgenticSeachTool::class, name = "agentic_search"),
    JsonSubTypes.Type(value = MCPTool::class, name = "mcp"),
    JsonSubTypes.Type(value = ImageGenerationTool::class, name = "image_generation"),
)
interface Tool {
    val type: String
}

/**
 * Represents a function tool that can be executed.
 *
 * @property type The type identifier for this tool, should be "function"
 * @property description Optional description of what the function does
 * @property name Optional name of the function
 * @property parameters Map of parameters the function accepts
 * @property strict Whether to enforce strict parameter validation
 */
data class FunctionTool(
    override val type: String = "function",
    val description: String? = null,
    val name: String? = null,
    val parameters: MutableMap<String, Any> = mutableMapOf(),
    val strict: Boolean = true,
) : Tool {
    init {
        parameters["additionalProperties"] = false
    }
}

/**
 * A tool that is managed by Masaic.
 *
 * @property type The type identifier for this tool
 */
data class MasaicManagedTool(
    override val type: String,
) : Tool

/**
 * Represents a user's geographical location.
 *
 * @property type The type of location data
 * @property city Optional city name
 * @property country Country code
 * @property region Optional region or state
 * @property timezone Optional timezone identifier
 */
data class UserLocation(
    val type: String,
    val city: String? = null,
    val country: String,
    val region: String? = null,
    val timezone: String? = null,
)

/**
 * Tool implementation for web search operations.
 *
 * @property type The type identifier for this tool, should be "web_search_preview"
 * @property domains List of domains to restrict search to
 * @property searchContextSize Size of context to include with search results
 * @property userLocation Optional user location for localized search results
 */
data class WebSearchTool(
    override val type: String,
    val domains: List<String> = emptyList(),
    @JsonProperty("search_context_size")
    val searchContextSize: String = "medium",
    @JsonProperty("user_location")
    val userLocation: UserLocation? = null,
) : Tool

/**
 * Request model for creating a response.
 *
 * @property model The model identifier to use for generating the response
 * @property input The input content or messages
 * @property instructions Optional instructions for guiding the response
 * @property maxOutputTokens Optional maximum number of tokens in the output
 * @property tools Optional list of tools available for the model to use
 * @property temperature Controls randomness in output generation (0.0-1.0)
 * @property previousResponseId Optional ID of a previous response to continue from
 * @property topP Optional nucleus sampling parameter
 * @property toolChoice Optional specification for tool selection
 * @property store Whether to store the response
 * @property stream Whether to stream the response
 * @property reasoning Optional reasoning configuration
 * @property metadata Optional metadata to attach to the response
 * @property truncation Optional truncation configuration
 * @property text Optional text configuration
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateResponseRequest(
    val model: String,
    var input: Any,
    val instructions: String? = null,
    @JsonProperty("max_output_tokens")
    val maxOutputTokens: Int? = null,
    var tools: List<Tool>? = null,
    val temperature: Double = 1.0,
    @JsonProperty("previous_response_id")
    val previousResponseId: String? = null,
    @JsonProperty("top_p")
    val topP: Double? = null,
    @JsonProperty("tool_choice")
    val toolChoice: String? = null,
    val store: Boolean = true,
    val stream: Boolean = false,
    val reasoning: Reasoning? = null,
    val metadata: Any? = null,
    val truncation: Response.Truncation? = null,
    val text: ResponseTextConfig? = null,
) {
    /**
     * Parses the input field to ensure it's in the correct format.
     *
     * @param objectMapper Jackson ObjectMapper for JSON conversion
     */
    fun parseInput(objectMapper: ObjectMapper) {
        if (!(input is String)) {
            input =
                objectMapper.readValue(
                    objectMapper.writeValueAsString(input),
                    object : TypeReference<List<InputMessageItem>>() {},
                )
            if (input is List<*>) {
                for (item in input as List<InputMessageItem>) {
                    item.parseContent(objectMapper)
                }
            }
        }
    }
}

/**
 * Response model for listing input message items.
 *
 * @property object Type of the response, always "list"
 * @property data List of input message items
 * @property firstId ID of the first item in the list
 * @property lastId ID of the last item in the list
 * @property hasMore Whether there are more items available
 */
data class ResponseInputItemList(
    val `object`: String = "list",
    val data: List<InputMessageItem>,
    @JsonProperty("first_id")
    val firstId: String?,
    @JsonProperty("last_id")
    val lastId: String?,
    @JsonProperty("has_more")
    val hasMore: Boolean,
)

/**
 * Represents an input message item in a conversation.
 *
 * @property role Role of the message sender (e.g., "user", "assistant")
 * @property content Content of the message
 * @property type Type of the message item
 * @property id Unique identifier for the message
 * @property arguments Arguments for a function call
 * @property name Name of the function
 * @property tool_call_id ID of the tool call
 * @property call_id ID of the function call
 * @property output Output from a function call
 * @property status Status of the message
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class InputMessageItem(
    @JsonProperty("role")
    val role: String? = null,
    @JsonProperty("content")
    var content: Any? = null,
    @JsonProperty("type")
    var type: String = "message",
    @JsonProperty("id")
    var id: String? = null,
    @JsonProperty("arguments")
    val arguments: String? = null,
    @JsonProperty("name")
    val name: String? = null,
    @JsonProperty("tool_call_id")
    val tool_call_id: String? = null,
    @JsonProperty("call_id")
    val call_id: String? = null,
    @JsonProperty("output")
    val output: String? = null,
    @JsonProperty("status")
    val status: String = "completed", // Note: This value is not returned by completion API, so we will assume completed.
    @JsonProperty("created_at")
    val createdAt: BigDecimal? = BigDecimal.valueOf(Instant.now().toEpochMilli()),
) {
    init {
        if (call_id != null) {
            if (output != null) {
                type = "function_call_output"
            } else {
                type = "function_call"
            }
        }

        if (id == null) {
            id = UUID.randomUUID().toString()
        }
    }

    fun parseContent(objectMapper: ObjectMapper) {
        if (content is String?) {
            content = listOf(InputMessageItemContent(text = content?.toString(), type = "input_text"))
        } else if (content is List<*>?) { // check if content is json array
            content = objectMapper.convertValue(content, object : TypeReference<List<InputMessageItemContent>>() {})
        } else if (content is Map<*, *>?) {
            content = objectMapper.convertValue(content, InputMessageItemContent::class.java)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InputMessageItem

        if (role != other.role) return false
        if (content != other.content) return false
        if (arguments != other.arguments) return false
        if (name != other.name) return false
        if (tool_call_id != other.tool_call_id) return false
        if (call_id != other.call_id) return false
        if (output != other.output) return false

        return true
    }

    override fun hashCode(): Int {
        var result = role?.hashCode() ?: 0
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (arguments?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (tool_call_id?.hashCode() ?: 0)
        result = 31 * result + (call_id?.hashCode() ?: 0)
        result = 31 * result + (output?.hashCode() ?: 0)
        return result
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InputMessageItemContent(
    val text: String? = null,
    val type: String,
    @JsonProperty("image_url")
    val imageUrl: String? = null,
    val detail: String? = "auto",
    @JsonProperty("file_id")
    val fileId: String? = null,
    @JsonProperty("file_data")
    val fileData: String? = null,
    @JsonProperty("filename")
    val fileName: String? = null,
    val annotations: ResponseOutputText.Annotation? = null,
)

data class InstrumentationMetadataInput(
    val genAISystem: String = "UNKNOWN",
    val modelName: String = "UNKNOWN",
    val modelProviderAddress: String = "UNKNOWN",
    val modelProviderPort: String = "UNKNOWN",
)

/**
 * Tool implementation for MCP (Model Control Panel) operations.
 *
 * @property type The type identifier for this tool, should be "mcp"
 * @property serverLabel Label for the server
 * @property serverUrl URL of the MCP server
 * @property requireApproval When approval is required for tool use
 * @property allowedTools List of tools allowed to be used
 */
data class MCPTool(
    override val type: String,
    @JsonProperty("server_label")
    val serverLabel: String,
    @JsonProperty("server_url")
    val serverUrl: String,
    @JsonIgnore // TODO: for now this will be always never. Will enable this in upcoming releases.
    @JsonProperty("require_approval")
    val requireApproval: String = "never",
    @JsonProperty("allowed_tools")
    val allowedTools: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
) : Tool

/**
 * Optional mask for inpainting within an image generation tool.
 *
 * @property imageUrl Optional URL of the mask image.
 * @property fileId Optional file ID of the mask image.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InputImageMask(
    @JsonProperty("image_url")
    val imageUrl: String? = null,
    @JsonProperty("file_id")
    val fileId: String? = null,
)

/**
 * Tool implementation for image generation (Configuration Aspect).
 * This data class holds the configuration parameters for the image generation tool.
 * `prompt` and `generationType` are provided dynamically by the LLM.
 *
 * @property type The type of the image generation tool. Always "image_generation".
 * @property background Background type for the generated image. One of "transparent", "opaque", or "auto". Default: "auto".
 * @property inputImageMask Optional mask for inpainting (used for 'edit' type typically).
 * @property model The image generation model to use. E.g., "dall-e-2", "dall-e-3", "gpt-image-1". Default: "gpt-image-1".
 * @property moderation Moderation level for the generated image. E.g., "low", "auto". Default: "auto".
 * @property outputCompression Compression level (0-100%) for the output image (WEBP/JPEG only). Default: 100.
 * @property outputFormat The output format of the generated image. One of "png", "webp", or "jpeg". Default: "png".
 * @property partialImages Number of partial images to generate in streaming mode (0-3). Default: 0.
 * @property quality The quality of the generated image. E.g., "low", "medium", "high", "auto" (for gpt-image-1); "standard", "hd" (for dall-e-3). Default: "auto".
 * @property size The size of the generated image. E.g., "1024x1024", "1024x1536", "1536x1024", "auto". Default: "auto".
 * @property n Number of images to generate. Default: 1.
 * @property responseFormat Format for dall-e-2/3: url or b64_json. gpt-image-1 always b64_json. Default: "url".
 * @property style Style for dall-e-3: vivid or natural. Default: "vivid".
 * @property user Unique identifier for end-user for abuse monitoring.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImageGenerationTool(
    override val type: String = "image_generation",
    val background: String? = "auto",
    @JsonProperty("input_image_mask")
    val inputImageMask: InputImageMask? = null,
    val model: String = "gpt-image-1",
    val moderation: String? = "auto",
    @JsonProperty("output_compression")
    val outputCompression: Int? = 100,
    @JsonProperty("output_format")
    val outputFormat: String? = "png",
    @JsonProperty("partial_images")
    val partialImages: Int? = 0,
    val quality: String? = "auto",
    val size: String? = "auto",
    val n: Int? = 1,
    @JsonProperty("response_format")
    val responseFormat: String? = "b64_json",
    val style: String? = "vivid",
    val user: String? = null,
    @JsonProperty("model_provider_key")
    val modelProviderKey: String? = null,
) : Tool

data class ModelInfo(
    val bearerToken: String?,
    val model: String?,
) {
    companion object {
        fun modelSettings(modelInfo: ModelInfo?): ModelSettings? {
            return if (modelInfo == null || modelInfo.bearerToken.isNullOrEmpty() || modelInfo.model.isNullOrEmpty()) return null else ModelSettings(modelInfo.bearerToken, modelInfo.model)
        }

        fun fromApiKey(
            apiKey: String?,
            model: String?,
        ): ModelInfo? {
            return if (apiKey.isNullOrEmpty() || model.isNullOrEmpty()) return null else ModelInfo(apiKey, model)
        }
    }
}
