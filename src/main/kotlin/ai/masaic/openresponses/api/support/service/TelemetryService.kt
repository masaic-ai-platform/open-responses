package ai.masaic.openresponses.api.support.service

import ai.masaic.openresponses.api.model.CreateResponseMetadataInput
import com.openai.core.jsonMapper
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Timer.Sample
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import kotlinx.coroutines.reactor.ReactorContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.coroutines.coroutineContext

//@Service
open class TelemetryService(
    private val observationRegistry: ObservationRegistry,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    open fun emitModelInputEvents(
        observation: Observation,
        inputParams: ChatCompletionCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        val mapper = jsonMapper()
        inputParams.messages().forEach { message ->
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
                } ?: return@forEach

            val eventData =
                mapOf(
                    "gen_ai.system" to metadata.genAISystem,
                    "role" to role,
                    "content" to content,
                )
            observation.event(
                Observation.Event.of(eventName, mapper.writeValueAsString(eventData)),
            )
        }
    }

    open fun emitModelOutputEvents(
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

                    observation.event(
                        Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
                    )
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
                observation.event(
                    Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
                )
            }
        }
    }

    open fun emitModelOutputEvents(
        observation: Observation,
        chatCompletion: ChatCompletion,
        metadata: CreateResponseMetadataInput,
    ) {
        val mapper = jsonMapper()
        chatCompletion.choices().forEach { choice ->
            if (choice.message().content().isPresent) {
                val eventData =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "role" to "assistant",
                        "content" to choice.message().content().get(),
                    )
                observation.event(
                    Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
                )
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
                observation.event(
                    Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
                )
            }
        }
    }

    fun setAllObservationAttributes(
        observation: Observation,
        response: Response,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
        finishReason: String,
    ) {
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "chat")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SYSTEM, metadata.genAISystem ?: "not_available")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OUTPUT_TYPE, "text")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_MODEL, response.model().asString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")
        observation.highCardinalityKeyValue(GenAIObsAttributes.RESPONSE_ID, response.id())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_FINISH_REASONS, finishReason)

        params.temperature().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TEMPERATURE, it.toString()) }
        params.maxOutputTokens().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_MAX_TOKENS, it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TOP_P, it.toString()) }

        response.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_INPUT_TOKENS, usage.inputTokens().toString())
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_OUTPUT_TOKENS, usage.outputTokens().toString())
        }
    }

    fun setAllObservationAttributes(
        observation: Observation,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "chat")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SYSTEM, metadata.genAISystem ?: "not_available")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OUTPUT_TYPE, "text")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_MODEL, chatCompletion.model())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")
        observation.highCardinalityKeyValue(GenAIObsAttributes.RESPONSE_ID, chatCompletion.id())

        params.temperature().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TEMPERATURE, it.toString()) }
        params.maxOutputTokens().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_MAX_TOKENS, it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TOP_P, it.toString()) }

        chatCompletion.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_INPUT_TOKENS, usage.promptTokens().toString())
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_OUTPUT_TOKENS, usage.completionTokens().toString())
        }

        setFinishReasons(observation, chatCompletion)
    }

    fun setFinishReasons(
        observation: Observation,
        chatCompletion: ChatCompletion,
    ) {
        val finishReasons =
            chatCompletion
                .choices()
                .mapNotNull {
                    it
                        .finishReason()
                        .value()
                        ?.name
                        ?.lowercase()
                }.distinct()
        if (finishReasons.isNotEmpty()) {
            observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_FINISH_REASONS, finishReasons.joinToString(","))
        }
    }

    suspend fun startObservation(obsName: String): Observation {
        val parentObservation =
            coroutineContext[ReactorContext]?.context?.get<Observation>(
                ObservationThreadLocalAccessor.KEY,
            )
        val observation = Observation.createNotStarted(obsName, observationRegistry)
        if (parentObservation?.isNoop != true) {
            observation.parentObservation(parentObservation)
        }
        observation.start()
        return observation
    }

    fun stopObservation(
        observation: Observation,
        response: Response,
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
    ) {
        emitModelOutputEvents(observation, response, metadata)
        setAllObservationAttributes(observation, response, params, metadata, "stop")
        recordTokenUsage(metadata, response, params, "input")
        recordTokenUsage(metadata, response, params, "output")
        observation.stop()
    }

    fun <T> withClientObservation(
        obsName: String,
        parentObservation: Observation? = null,
        block: (Observation) -> T,
    ): T {
        val observation = Observation.createNotStarted(obsName, observationRegistry)
        if (parentObservation != null) observation.parentObservation(parentObservation)
        observation.start()
        return try {
            block(observation)
        } catch (e: Exception) {
            observation.error(e)
            observation.lowCardinalityKeyValue(GenAIObsAttributes.ERROR_TYPE, "${e.javaClass}")
            throw e
        } finally {
            observation.stop()
        }
    }

    fun recordTokenUsage(
        metadata: CreateResponseMetadataInput,
        response: Response,
        params: ResponseCreateParams,
        tokenType: String,
    ) {
        val tokenCount =
            if (tokenType == "input" && response.usage().isPresent) {
                response.usage().get().inputTokens()
            } else if (response.usage().isPresent) {
                response.usage().get().outputTokens()
            } else {
                0
            }
        tokenUsage(metadata, params.model().asString(), params.model().asString(), tokenType, tokenCount)
    }

    fun recordTokenUsage(
        metadata: CreateResponseMetadataInput,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        tokenType: String,
        tokenCount: Long,
    ) {
        val requestModel = params.model().asString()
        val responseMode = chatCompletion.model()
        tokenUsage(metadata, responseMode, requestModel, tokenType, tokenCount)
    }

    private fun tokenUsage(
        metadata: CreateResponseMetadataInput,
        responseMode: String,
        requestModel: String,
        tokenType: String,
        tokenCount: Long,
    ) {
        val summaryBuilder =
            DistributionSummary
                .builder(GenAIObsAttributes.CLIENT_TOKEN_USAGE)
                .description("Measures number of input and output tokens used")
                .baseUnit("token")
                .tags(
                    GenAIObsAttributes.OPERATION_NAME,
                    "chat",
                    GenAIObsAttributes.SYSTEM,
                    metadata.genAISystem ?: "not_available",
                    "gen_ai.token.type",
                    tokenType,
                )
        summaryBuilder.tag(GenAIObsAttributes.REQUEST_MODEL, requestModel)
        summaryBuilder.tag(GenAIObsAttributes.RESPONSE_MODEL, responseMode)
        summaryBuilder.tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")
        val summary = summaryBuilder.register(meterRegistry)
        summary.record(tokenCount.toDouble())
    }

    fun <T> withTimer(
        params: ResponseCreateParams,
        metadata: CreateResponseMetadataInput,
        block: () -> T,
    ): T {
        val timerBuilder =
            Timer
                .builder(GenAIObsAttributes.OPERATION_DURATION)
                .description("GenAI operation duration")
                .tags(GenAIObsAttributes.OPERATION_NAME, "chat", GenAIObsAttributes.SYSTEM, metadata.genAISystem)
                .tag(GenAIObsAttributes.REQUEST_MODEL, params.model().asString())
                .tag(GenAIObsAttributes.RESPONSE_MODEL, params.model().asString())
                .tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")
        val timer = timerBuilder.register(meterRegistry)
        val sample = Timer.start(meterRegistry)
        try {
            return block()
        } catch (ex: Exception) {
            timerBuilder.tag(GenAIObsAttributes.ERROR_TYPE, "${ex.javaClass}")
            throw ex
        } finally {
            sample.stop(timer)
        }
    }

    fun genAiDurationSample(): Sample {
        val sample = Timer.start(meterRegistry)
        return sample
    }

    fun stopGenAiDurationSample(
        metadata: CreateResponseMetadataInput,
        params: ResponseCreateParams,
        sample: Sample,
    ) {
        val timerBuilder =
            Timer
                .builder(GenAIObsAttributes.OPERATION_DURATION)
                .description("GenAI operation duration")
                .tags(GenAIObsAttributes.OPERATION_NAME, "chat", GenAIObsAttributes.SYSTEM, metadata.genAISystem)
                .tags(GenAIObsAttributes.REQUEST_MODEL, params.model().asString())
                .tags(GenAIObsAttributes.RESPONSE_MODEL, params.model().asString())
                .tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress ?: "not_available")
        val timer = timerBuilder.register(meterRegistry)
        sample.stop(timer)
    }

    fun getCurrentObservation(): Observation? = observationRegistry.currentObservation
}
