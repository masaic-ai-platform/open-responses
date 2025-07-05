package ai.masaic.openresponses.api.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
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
    val response_format: Map<String, Any>? = null,
    val seed: Long? = null,
    val stop: Any? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val top_p: Double? = null,
    var tools: List<Map<String, Any>>? = null,
    val tool_choice: Any? = null,
    val user: String? = null,
    val store: Boolean = false,
    val metadata: ChatCompletionCreateParams.Metadata? = null,
    val stream_options: Map<String, Any>? = null,
    @JsonAlias("extra_body")
    val extraBody: Map<String, Any>? = null,
) {
    fun parseMessages(mapper: ObjectMapper): List<ChatCompletionMessageParam> {
        val typeReference = mapper.typeFactory.constructParametricType(List::class.java, ChatCompletionMessageParam::class.java)
        return mapper.convertValue(messages, typeReference)
    }
}
