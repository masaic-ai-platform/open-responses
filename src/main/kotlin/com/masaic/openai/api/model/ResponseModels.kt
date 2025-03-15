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

// Response models
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResponseObject(
    val id: String,
    val `object`: String = "response",
    @JsonProperty("created_at") val createdAt: Long = Instant.now().epochSecond,
    val status: ResponseStatus,
    val error: ErrorObject? = null,
    @JsonProperty("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
    val instructions: String? = null,
    @JsonProperty("max_output_tokens") val maxOutputTokens: Int? = null,
    val model: String,
    val output: List<ResponseOutput>,
    @JsonProperty("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @JsonProperty("previous_response_id") val previousResponseId: String? = null,
    val reasoning: ReasoningOutput? = null,
    val store: Boolean? = null,
    val temperature: Double? = null,
    val text: TextConfig? = null,
    @JsonProperty("tool_choice") val toolChoice: Any? = null,
    val tools: List<Any>? = null,
    @JsonProperty("top_p") val topP: Double? = null,
    val truncation: String? = null,
    val usage: UsageInfo? = null,
    val user: String? = null,
    val metadata: Map<String, Any>? = null,
    @JsonProperty("output_text") val outputText: OutputText? = null
)

enum class ResponseStatus {
    in_progress, completed, incomplete, failed
}

data class ErrorObject(
    val code: String? = null,
    val message: String,
    val param: String? = null,
    val type: String
)

data class IncompleteDetails(
    val reason: IncompleteReason
)

enum class IncompleteReason {
    max_output_tokens, content_filter
}

data class ReasoningOutput(
    val effort: String? = null,
    val summary: ReasoningSummary? = null
)

data class ReasoningSummary(
    val type: String = "summary_text",
    val text: String
)

data class TextConfig(
    val format: TextFormatConfig
)

data class TextFormatConfig(
    val format: Map<String, String>
)

data class UsageInfo(
    @JsonProperty("input_tokens") val inputTokens: Int,
    @JsonProperty("input_tokens_details") val inputTokensDetails: InputTokensDetails? = null,
    @JsonProperty("output_tokens") val outputTokens: Int,
    @JsonProperty("output_tokens_details") val outputTokensDetails: OutputTokensDetails? = null,
    @JsonProperty("total_tokens") val totalTokens: Int
)

data class InputTokensDetails(
    @JsonProperty("cached_tokens") val cachedTokens: Int
)

data class OutputTokensDetails(
    @JsonProperty("reasoning_tokens") val reasoningTokens: Int
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = Message::class, name = "message")
)
sealed class ResponseOutput {
    @get:JsonIgnore
    abstract val type: String
    abstract val id: String
    abstract val status: ResponseStatus
}

data class Message(
    override val type: String = "message",
    override val id: String,
    override val status: ResponseStatus,
    val role: String = "assistant",
    val content: List<MessageContent>
) : ResponseOutput()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutputText::class, name = "output_text")
)
sealed class MessageContent {
    @get:JsonIgnore
    abstract val type: String
}

data class OutputText(
    override val type: String = "output_text",
    val text: String,
    val annotations: List<Annotation>? = null
) : MessageContent()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FileCitation::class, name = "file_citation"),
    JsonSubTypes.Type(value = WebCitation::class, name = "web_citation")
)
sealed class Annotation {
    @get:JsonIgnore
    abstract val type: String
}

data class FileCitation(
    override val type: String = "file_citation",
    @JsonProperty("file_id") val fileId: String,
    val quote: String,
    @JsonProperty("start_index") val startIndex: Int? = null,
    @JsonProperty("end_index") val endIndex: Int? = null
) : Annotation()

data class WebCitation(
    override val type: String = "web_citation",
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    @JsonProperty("start_index") val startIndex: Int? = null,
    @JsonProperty("end_index") val endIndex: Int? = null
) : Annotation()

// Input Item models
data class InputItemList(
    val `object`: String = "list",
    val data: List<InputItem>,
    @JsonProperty("first_id") val firstId: String,
    @JsonProperty("last_id") val lastId: String,
    @JsonProperty("has_more") val hasMore: Boolean
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageInputItem::class, name = "message"),
    JsonSubTypes.Type(value = ToolCodeInputItem::class, name = "tool_code"),
    JsonSubTypes.Type(value = ToolFileInputItem::class, name = "tool_file"),
    JsonSubTypes.Type(value = ToolFunctionInputItem::class, name = "tool_function")
)
sealed class InputItem {
    abstract val id: String
    @get:JsonIgnore
    abstract val type: String
    abstract val status: ResponseStatus?
}

data class MessageInputItem(
    override val id: String,
    override val type: String = "message",
    override val status: ResponseStatus? = null,
    val role: String,
    val content: List<InputItemContent>
) : InputItem()

data class ToolCodeInputItem(
    override val id: String,
    override val type: String = "tool_code",
    override val status: ResponseStatus? = null,
    @JsonProperty("call_id") val callId: String,
    val output: String
) : InputItem()

data class ToolFileInputItem(
    override val id: String,
    override val type: String = "tool_file",
    override val status: ResponseStatus? = null,
    @JsonProperty("call_id") val callId: String,
    val output: String
) : InputItem()

data class ToolFunctionInputItem(
    override val id: String,
    override val type: String = "tool_function",
    override val status: ResponseStatus? = null,
    @JsonProperty("call_id") val callId: String,
    val output: String
) : InputItem()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = InputText::class, name = "input_text"),
    JsonSubTypes.Type(value = InputImage::class, name = "input_image")
)
sealed class InputItemContent {
    @get:JsonIgnore
    abstract val type: String
}

data class InputText(
    override val type: String = "input_text",
    val text: String
) : InputItemContent()

data class InputImage(
    override val type: String = "input_image",
    @JsonProperty("image_url") val imageUrl: String
) : InputItemContent()

// Streaming Event models
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ResponseCreatedEvent::class, name = "response.created"),
    JsonSubTypes.Type(value = ResponseInProgressEvent::class, name = "response.in_progress"),
    JsonSubTypes.Type(value = ResponseCompletedEvent::class, name = "response.completed"),
    JsonSubTypes.Type(value = ResponseFailedEvent::class, name = "response.failed"),
    JsonSubTypes.Type(value = ResponseIncompleteEvent::class, name = "response.incomplete"),
    JsonSubTypes.Type(value = ResponseOutputItemAddedEvent::class, name = "response.output_item.added"),
    JsonSubTypes.Type(value = ResponseOutputItemDoneEvent::class, name = "response.output_item.done"),
    JsonSubTypes.Type(value = ResponseContentPartAddedEvent::class, name = "response.content_part.added"),
    JsonSubTypes.Type(value = ResponseContentPartDoneEvent::class, name = "response.content_part.done"),
    JsonSubTypes.Type(value = ResponseOutputTextDeltaEvent::class, name = "response.output_text.delta"),
    JsonSubTypes.Type(value = ResponseOutputTextAnnotationAddedEvent::class, name = "response.output_text.annotation.added"),
    JsonSubTypes.Type(value = ResponseOutputTextDoneEvent::class, name = "response.output_text.done"),
    JsonSubTypes.Type(value = ResponseRefusalDeltaEvent::class, name = "response.refusal.delta"),
    JsonSubTypes.Type(value = ResponseRefusalDoneEvent::class, name = "response.refusal.done"),
    JsonSubTypes.Type(value = ResponseFunctionCallArgumentsDeltaEvent::class, name = "response.function_call_arguments.delta"),
    JsonSubTypes.Type(value = ResponseFunctionCallArgumentsDoneEvent::class, name = "response.function_call_arguments.done"),
    JsonSubTypes.Type(value = ResponseFileSearchCallInProgressEvent::class, name = "response.file_search_call.in_progress"),
    JsonSubTypes.Type(value = ResponseFileSearchCallSearchingEvent::class, name = "response.file_search_call.searching"),
    JsonSubTypes.Type(value = ResponseFileSearchCallCompletedEvent::class, name = "response.file_search_call.completed"),
    JsonSubTypes.Type(value = ResponseWebSearchCallInProgressEvent::class, name = "response.web_search_call.in_progress"),
    JsonSubTypes.Type(value = ResponseWebSearchCallSearchingEvent::class, name = "response.web_search_call.searching"),
    JsonSubTypes.Type(value = ResponseWebSearchCallCompletedEvent::class, name = "response.web_search_call.completed"),
    JsonSubTypes.Type(value = ErrorEvent::class, name = "error")
)
sealed class StreamingEvent {
    @get:JsonIgnore
    abstract val type: String
}

data class ResponseCreatedEvent(
    override val type: String = "response.created",
    val response: ResponseObject
) : StreamingEvent()

data class ResponseInProgressEvent(
    override val type: String = "response.in_progress",
    val response: ResponseObject
) : StreamingEvent()

data class ResponseCompletedEvent(
    override val type: String = "response.completed",
    val response: ResponseObject
) : StreamingEvent()

data class ResponseFailedEvent(
    override val type: String = "response.failed",
    val response: ResponseObject
) : StreamingEvent()

data class ResponseIncompleteEvent(
    override val type: String = "response.incomplete",
    val response: ResponseObject
) : StreamingEvent()

data class ResponseOutputItemAddedEvent(
    override val type: String = "response.output_item.added",
    @JsonProperty("output_index") val outputIndex: Int,
    val item: ResponseOutput
) : StreamingEvent()

data class ResponseOutputItemDoneEvent(
    override val type: String = "response.output_item.done",
    @JsonProperty("output_index") val outputIndex: Int,
    val item: ResponseOutput
) : StreamingEvent()

data class ResponseContentPartAddedEvent(
    override val type: String = "response.content_part.added",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    val part: MessageContent
) : StreamingEvent()

data class ResponseContentPartDoneEvent(
    override val type: String = "response.content_part.done",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    val part: MessageContent
) : StreamingEvent()

data class ResponseOutputTextDeltaEvent(
    override val type: String = "response.output_text.delta",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    val delta: String
) : StreamingEvent()

data class ResponseOutputTextAnnotationAddedEvent(
    override val type: String = "response.output_text.annotation.added",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    @JsonProperty("annotation_index") val annotationIndex: Int,
    val annotation: Annotation
) : StreamingEvent()

data class ResponseOutputTextDoneEvent(
    override val type: String = "response.output_text.done",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    val text: String
) : StreamingEvent()

data class ResponseRefusalDeltaEvent(
    override val type: String = "response.refusal.delta",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    val delta: String
) : StreamingEvent()

data class ResponseRefusalDoneEvent(
    override val type: String = "response.refusal.done",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("content_index") val contentIndex: Int,
    val refusal: String
) : StreamingEvent()

data class ResponseFunctionCallArgumentsDeltaEvent(
    override val type: String = "response.function_call_arguments.delta",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    val delta: String
) : StreamingEvent()

data class ResponseFunctionCallArgumentsDoneEvent(
    override val type: String = "response.function_call_arguments.done",
    @JsonProperty("item_id") val itemId: String,
    @JsonProperty("output_index") val outputIndex: Int,
    val arguments: String
) : StreamingEvent()

data class ResponseFileSearchCallInProgressEvent(
    override val type: String = "response.file_search_call.in_progress",
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("item_id") val itemId: String
) : StreamingEvent()

data class ResponseFileSearchCallSearchingEvent(
    override val type: String = "response.file_search_call.searching",
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("item_id") val itemId: String
) : StreamingEvent()

data class ResponseFileSearchCallCompletedEvent(
    override val type: String = "response.file_search_call.completed",
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("item_id") val itemId: String
) : StreamingEvent()

data class ResponseWebSearchCallInProgressEvent(
    override val type: String = "response.web_search_call.in_progress",
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("item_id") val itemId: String
) : StreamingEvent()

data class ResponseWebSearchCallSearchingEvent(
    override val type: String = "response.web_search_call.searching",
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("item_id") val itemId: String
) : StreamingEvent()

data class ResponseWebSearchCallCompletedEvent(
    override val type: String = "response.web_search_call.completed",
    @JsonProperty("output_index") val outputIndex: Int,
    @JsonProperty("item_id") val itemId: String
) : StreamingEvent()

data class ErrorEvent(
    override val type: String = "error",
    val code: String,
    val message: String,
    val param: String? = null
) : StreamingEvent() 