package ai.masaic.openresponses.api.support.service

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import com.openai.core.jsonMapper
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.Response
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Service

@Service
class LangfuseTelemetryService(observationRegistry: ObservationRegistry, private val meterRegistry: MeterRegistry) : TelemetryService(observationRegistry, meterRegistry) {

    override fun emitModelInputEvents(
        observation: Observation,
        inputParams: ChatCompletionCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
//        inputParams.messages().forEachIndexed { index, message ->
        val message = inputParams.messages().lastOrNull() ?: return
            val (role, eventName, content) =
                when {
                    message.isUser() ->
                        Triple("user", GenAIObsAttributes.USER_MESSAGE, message.user().get().content())
                    message.isAssistant() &&
                            message
                                .assistant()
                                .get()
                                .content()
                                .isPresent ->
                        Triple("assistant", GenAIObsAttributes.ASSISTANT_MESSAGE, message.assistant().get().content())
                    message.isAssistant() &&
                            message
                                .assistant()
                                .get()
                                .toolCalls()
                                .isPresent -> {
                        val tools =
                            message.assistant().get().toolCalls().get().map { tool ->
                                mapOf(
                                    "id" to tool.id(),
                                    "function" to
                                            mapOf(
                                                "name" to tool.function().name(),
                                                "arguments" to tool.function().arguments(),
                                            ),
                                )
                            }
                        Triple("assistant", GenAIObsAttributes.ASSISTANT_MESSAGE, mapOf("tool_calls" to tools))
                    }
                    message.isTool() ->
                        Triple(
                            "tool",
                            GenAIObsAttributes.TOOL_MESSAGE,
                            mapOf("id" to message.tool().get().toolCallId(), "content" to message.tool().get().content()),
                        )
                    message.isSystem() && message.system().isPresent ->
                        Triple("system", GenAIObsAttributes.SYSTEM_MESSAGE, message.system().get().content())
                    message.isDeveloper() && message.developer().isPresent ->
                        Triple("system", GenAIObsAttributes.SYSTEM_MESSAGE, message.developer().get().content())
                    else -> null
                } ?: return

            observation.highCardinalityKeyValue("gen_ai.prompt.0.role", role)
            observation.highCardinalityKeyValue("gen_ai.prompt.0.content", content.toString())
//            observation.highCardinalityKeyValue("gen_ai.prompt.$index.role", role)
//            observation.highCardinalityKeyValue("gen_ai.prompt.$index.content", content.toString())
//        }
    }

    override fun emitModelOutputEvents(
        observation: Observation,
        response: Response,
        metadata: CreateResponseMetadataInput,
    ) {
        val mapper = jsonMapper()
        if (response.output().isNotEmpty()) {
            val toolCallMap = mutableMapOf<String, Any>()
            response.output().forEachIndexed { index, output ->
                if (output.isMessage()) {
                    val eventData = mutableMapOf<String, Any>()
                    if (response.output().size > 1) {
                        eventData["index"] = index
                        eventData["finish_reason"] = "stop"
                    }
                    eventData["gen_ai.system"] = metadata.genAISystem ?: "not_available"
                    eventData["role"] = "assistant"
                    eventData["content"] = output.asMessage().content()

                    observation.highCardinalityKeyValue("gen_ai.completion.$index.role", "assistant")
                    observation.highCardinalityKeyValue("gen_ai.completion.$index.content", output.asMessage().content().toString())
//                    observation.event(
//                        Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
//                    )
                }

                if (output.isFunctionCall()) {
                    val toolCall = output.asFunctionCall()
                    toolCallMap["id"] = toolCall.id()
                    toolCallMap["type"] = "function"
                    toolCallMap["function"] =
                        mapOf(
                            "name" to toolCall.name(),
                            "arguments" to toolCall.arguments(),
                        )
                }
            }

            if (toolCallMap.isNotEmpty()) {
                val eventData =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "finish_reason" to "tool_calls",
                        "index" to 0, // TODO:: to revisit later with deeper look in open telemetry specs
                        "tool_calls" to toolCallMap,
                    )
                observation.highCardinalityKeyValue("gen_ai.completion.0.role", "tool_calls")
                observation.highCardinalityKeyValue("gen_ai.completion.0.content", toolCallMap.toString())
//                observation.event(
//                    Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
//                )
            }
        }
    }

    override fun emitModelOutputEvents(
        observation: Observation,
        chatCompletion: ChatCompletion,
        metadata: CreateResponseMetadataInput,
    ) {
        val mapper = jsonMapper()
        chatCompletion.choices().forEachIndexed{ index, choice ->
            if (choice.message().content().isPresent) {
                val eventData =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "role" to "assistant",
                        "content" to choice.message().content().get(),
                    )
                observation.highCardinalityKeyValue("gen_ai.completion.$index.role", "assistant")
                observation.highCardinalityKeyValue("gen_ai.completion.$index.content", choice.message().content().get())
//                observation.event(
//                    Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
//                )
            }
            if (choice.finishReason().asString() == "tool_calls") {
                val toolCalls =
                    choice.message().toolCalls().get().map { tool ->
                        mapOf(
                            "id" to tool.id(),
                            "type" to "function",
                            "function" to
                                    mapOf(
                                        "name" to tool.function().name(),
                                        "arguments" to tool.function().arguments(),
                                    ),
                        )
                    }
                val eventData =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "finish_reason" to choice.finishReason().asString(),
                        "index" to choice.index(),
                        "tool_calls" to toolCalls,
                    )
                observation.highCardinalityKeyValue("gen_ai.completion.${choice.index()}.role", "tool_calls")
                observation.highCardinalityKeyValue("gen_ai.completion.${choice.index()}.content", toolCalls.toString())
//                observation.event(
//                    Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
//                )
            }
        }
    }
}
