package com.masaic.openai.api.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

// Request models
data class CreateResponseRequest(
    val model: String,
    val input: Any,
    val tools: List<Any>? = null,
    @JsonProperty("tool_choice") val toolChoice: String? = null,
    val instructions: String? = null,
    val stream: Boolean? = null,
    val reasoning: ReasoningConfig? = null,
    val format: ResponseFormat? = null
)

data class ReasoningConfig(
    val effort: String? = null
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = TextFormat::class, name = "text"),
    JsonSubTypes.Type(value = JsonSchemaFormat::class, name = "json_schema"),
    JsonSubTypes.Type(value = JsonObjectFormat::class, name = "json_object")
)
sealed class ResponseFormat {
    abstract val type: String
}

data class TextFormat(
    override val type: String = "text"
) : ResponseFormat()

data class JsonSchemaFormat(
    override val type: String = "json_schema",
    val description: String? = null,
    val name: String? = null,
    val schema: Map<String, Any>,
    val strict: Boolean? = false
) : ResponseFormat()

data class JsonObjectFormat(
    override val type: String = "json_object"
) : ResponseFormat()