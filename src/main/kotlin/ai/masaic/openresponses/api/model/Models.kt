package ai.masaic.openresponses.api.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.Metadata
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
    val store: Boolean? = null,
    val stream: Boolean = false,
    val reasoning: Reasoning? = null,
    val metadata: Metadata? = null,
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
