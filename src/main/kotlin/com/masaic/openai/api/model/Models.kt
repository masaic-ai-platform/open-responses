package com.masaic.openai.api.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.Metadata
import com.openai.models.responses.Response

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
    val description: String? = null,
    val name: String? = null,
    val parameters: MutableMap<String, Any> = mutableMapOf(),
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
    val userLocation: UserLocation? = null
) : Tool()

// Request models
data class CreateResponseRequest(
    val model: String,
    var input: Any,
    val instructions: String? = null,
    @JsonProperty("max_output_tokens")
    val maxOutputTokens: Int? = null,
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    val previousResponseId: String? = null,
    @JsonProperty("top_p")
    val topP: Double? = null,
    @JsonProperty("tool_choice")
    val toolChoice: String? = null,
    val store: Boolean? = null,
    val stream: Boolean? = null,
    val reasoning: Reasoning? = null,
    val metadata: Metadata? = null,
    val truncation: Response.Truncation?=null,
    val text: ResponseTextConfig? = null,
){

    fun parseInput(objectMapper: ObjectMapper){
        if(!(input is String)) {
            input = objectMapper.readValue(
                objectMapper.writeValueAsString(input),
                object : TypeReference<List<InputMessageItem>>() {}
            )
        }
    }
}

// Response for listing InputItems
data class ResponseItemList(
    val `object`: String = "list",
    val data: List<InputMessageItem>,
    @JsonProperty("first_id")
    val firstId: String?,
    @JsonProperty("last_id")
    val lastId: String?,
    @JsonProperty("has_more")
    val hasMore: Boolean
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InputMessageItem(
    val role: String? = null,
    val content: Any? = null,
    var type: String = "message",
    val id: String? = null,
    val arguments: String? = null,
    val name: String? = null,
    val tool_call_id: String? = null,
    val call_id: String? = null,
    val output: String? = null,
) {
    init {
        if (call_id != null) {
            if (output != null) {
                type = "function_call_output"
            } else {
                type = "function_call"
            }
        }
    }
}
