package com.masaic.openai.api.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.Metadata

data class Reasoning(
    val effort: String? = null,
    val summary: String? = null
)

data class FileSearchTool(
    val filters: Any? = null,
    @JsonProperty("max_num_results")
    val maxNumResults: Int = 20,
    @JsonProperty("ranking_options")
    val rankingOptions: RankingOptions,
    @JsonProperty("vector_store_ids")
    val vectorStoreIds: List<String>
) : Tool()

data class RankingOptions(
    val ranker: String = "auto",
    @JsonProperty("score_threshold")
    val scoreThreshold: Double = 0.0
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = FunctionTool::class, name = "function"),
    JsonSubTypes.Type(value = WebSearchTool::class, name = "web_search_preview"),
    JsonSubTypes.Type(value = FileSearchTool::class, name = "file_search")
)
sealed class Tool

data class FunctionTool(
    val description: String,
    val name: String,
    val parameters: MutableMap<String, Any>,
    val strict: Boolean = true
) : Tool() {
    init {
        parameters["additionalProperties"] = false
    }
}

data class UserLocation(
    val type: String,
    val city: String? = null,
    val country: String,
    val region: String? = null,
    val timezone: String? = null
)

data class WebSearchTool(
    val domains: List<String> = emptyList(),
    @JsonProperty("search_context_size")
    val searchContextSize: String = "medium",
    @JsonProperty("user_location")
    val userLocation: UserLocation
) : Tool()

// Request models
data class CreateResponseRequest(
    val model: String,
    val input: Any,
    val instructions: String? = null,
    @JsonProperty("max_output_tokens")
    val maxOutputTokens: Int? = null,
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    @JsonProperty("top_p")
    val topP: Double? = null,
    @JsonProperty("tool_choice")
    val toolChoice: String? = null,
    val stream: Boolean? = null,
    val reasoning: Reasoning? = null,
    val metadata: Metadata? = null,
    val text: ResponseTextConfig? = null,
)

// Response for listing InputItems
data class ResponseItemList(
    val `object`: String = "list",
    val data: List<InputItem>,
    @JsonProperty("first_id")
    val firstId: String?,
    @JsonProperty("last_id")
    val lastId: String?,
    @JsonProperty("has_more")
    val hasMore: Boolean
)

// InputItem
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = InputMessageItem::class, name = "message")
)
sealed class InputItem {
    abstract val id: String
}

data class InputMessageItem(
    override val id: String,
    val role: String,
    val content: List<InputContent>
) : InputItem()

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = InputText::class, name = "input_text"),
    JsonSubTypes.Type(value = InputImage::class, name = "input_image")
)
sealed class InputContent

data class InputText(
    val text: String
) : InputContent()

data class InputImage(
    @JsonProperty("image_url")
    val imageUrl: String
) : InputContent()