package ai.masaic.openresponses.api.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import java.util.*

data class CreateCompletionRequest(
    val messages: List<Map<String, Any>>,
    val model: String,
    val frequency_penalty: Double? = null,
    val logit_bias: Map<String, Int>? = null,
    val logprobs: Boolean? = null,
    val top_logprobs: Int? = null,
    val max_tokens: Int? = null,
    val n: Int? = null,
    val presence_penalty: Double? = null,
    val response_format: Map<String, String>? = null,
    val seed: Long? = null,
    val stop: Any? = null,
    val stream: Boolean? = null,
    val temperature: Double? = null,
    val top_p: Double? = null,
    var tools: List<ChatCompletionTool>? = null,
    val tool_choice: Any? = null,
    val user: String? = null,
    val store: Boolean = false,
    @JsonProperty("extra_body")
    val extraBody: Map<String, Any>? = null,
) {
    fun parseMessages(mapper: ObjectMapper): List<ChatCompletionMessageParam> {
        val typeReference = mapper.typeFactory.constructParametricType(List::class.java, ChatCompletionMessageParam::class.java)
        return mapper.convertValue(messages, typeReference)
    }
}
