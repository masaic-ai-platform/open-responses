package ai.masaic.openresponses.api.support.service

import ai.masaic.openresponses.api.model.InstrumentationMetadataInput
import com.openai.core.jsonMapper
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Timer.Sample
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import kotlin.jvm.optionals.getOrDefault

@Service
class TelemetryService(
    private val observationRegistry: ObservationRegistry,
    val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    @Value("\${otel.instrumentation.genai.capture.message.content:true}")
    private val captureMessageContent: Boolean = true

    fun emitModelInputEvents(
        observation: Observation,
        inputParams: ChatCompletionCreateParams,
        metadata: InstrumentationMetadataInput,
    ) {
        val mapper = jsonMapper()
        inputParams.messages().forEach { message ->
            val (role, eventName, content) =
                when {
                    message.isUser() -> {
                        val content =
                            if (message
                                    .user()
                                    .get()
                                    .content()
                                    .isText()
                            ) {
                                messageContent(
                                    message
                                        .user()
                                        .get()
                                        .content()
                                        .asText(),
                                )
                            } else {
                                messageContent(mapper.writeValueAsString(message.user().get().content()))
                            }
                        Triple(
                            "user",
                            GenAIObsAttributes.USER_MESSAGE,
                            content,
                        )
                    }
                    message.isAssistant() &&
                        message
                            .assistant()
                            .get()
                            .toolCalls()
                            .isPresent -> {
                        val tools =
                            message.assistant().get().toolCalls().get().map { tool ->
                                val map = mutableMapOf("name" to tool.function().name())
                                putIfNotEmpty(map, "arguments", messageContent(tool.function().arguments()))

                                mapOf(
                                    "id" to tool.id(),
                                    "function" to map,
                                )
                            }
                        val finalMap =
                            mapOf(
                                "gen_ai.system" to metadata.genAISystem,
                                "role" to "assistant",
                                "tool_calls" to tools,
                            )
                        observation.event(
                            Observation.Event.of(GenAIObsAttributes.ASSISTANT_MESSAGE, mapper.writeValueAsString(finalMap)),
                        )
                        Triple("", "", "") // TODO:: to be fixed properly... workaround for tool calls message
                    }
                    message.isAssistant() &&
                        message
                            .assistant()
                            .get()
                            .content()
                            .isPresent &&
                        message
                            .assistant()
                            .get()
                            .toolCalls()
                            .isEmpty ->
                        Triple(
                            "assistant",
                            GenAIObsAttributes.ASSISTANT_MESSAGE,
                            messageContent(
                                message
                                    .assistant()
                                    .get()
                                    .content()
                                    .get()
                                    .asText(),
                            ),
                        )
                    message.isTool() -> {
                        val map = mutableMapOf("id" to message.tool().get().toolCallId())
                        putIfNotEmpty(
                            map,
                            "content",
                            messageContent(
                                message
                                    .tool()
                                    .get()
                                    .content()
                                    .asText(),
                            ),
                        )
                        val finalMap =
                            mapOf(
                                "gen_ai.system" to metadata.genAISystem,
                                "role" to "tool",
                                "tool_calls" to map,
                            )
                        observation.event(
                            Observation.Event.of(GenAIObsAttributes.TOOL_MESSAGE, mapper.writeValueAsString(finalMap)),
                        )
                        Triple("", "", "") // TODO:: to be fixed properly... workaround for tool message
                    }
                    message.isSystem() && message.system().isPresent ->
                        Triple(
                            "system",
                            GenAIObsAttributes.SYSTEM_MESSAGE,
                            messageContent(
                                message
                                    .system()
                                    .get()
                                    .content()
                                    .asText(),
                            ),
                        )
                    message.isDeveloper() && message.developer().isPresent ->
                        Triple(
                            "system",
                            GenAIObsAttributes.SYSTEM_MESSAGE,
                            messageContent(
                                message
                                    .developer()
                                    .get()
                                    .content()
                                    .asText(),
                            ),
                        )
                    else -> null
                } ?: return@forEach

            val eventData =
                if (content is String) {
                    val map =
                        mutableMapOf(
                            "gen_ai.system" to metadata.genAISystem,
                            "role" to role,
                        )
                    putIfNotEmpty(map, "content", content)
                    map
                } else {
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "role" to role,
                        "content" to content,
                    )
                }
            if (!(role.isEmpty() && (content is String && content.isEmpty()) && eventName.isEmpty())) {
                observation.event(
                    Observation.Event.of(eventName, mapper.writeValueAsString(eventData)),
                )
            }
        }
    }

    fun emitModelOutputEvents(
        observation: Observation,
        response: Response,
        metadata: InstrumentationMetadataInput,
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
                    if (captureMessageContent) {
                        eventData["content"] = output.asMessage().content()
                    }

                    observation.event(
                        Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
                    )
                }

                if (output.isFunctionCall()) {
                    val toolCall = output.asFunctionCall()

                    val functionDetailsMap = mutableMapOf("name" to toolCall.name())
                    putIfNotEmpty(functionDetailsMap, "arguments", messageContent(toolCall.arguments()))

                    toolCallMap["id"] = toolCall.id()
                    toolCallMap["type"] = "function"
                    toolCallMap["function"] = functionDetailsMap
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

    fun emitModelOutputEvents(
        observation: Observation,
        chatCompletion: ChatCompletion,
        metadata: InstrumentationMetadataInput,
    ) {
        val mapper = jsonMapper()
        chatCompletion.choices().forEach { choice ->
            val eventData: MutableMap<String, Any?> =
                mutableMapOf(
                    "gen_ai.system" to metadata.genAISystem,
                    "role" to "assistant",
                )
            val content = messageContent(choice.message().content().getOrDefault(""))
            if (content.isNotEmpty()) {
                eventData["content"] = content
            }

            if (choice.finishReason().asString() == "tool_calls") {
                val toolCalls =
                    choice.message().toolCalls().get().map { tool ->
                        val functionDetailsMap = mutableMapOf("name" to tool.function().name())
                        putIfNotEmpty(functionDetailsMap, "arguments", messageContent(tool.function().arguments()))
                        mapOf(
                            "id" to tool.id(),
                            "type" to "function",
                            "function" to functionDetailsMap,
                        )
                    }
                val tooCallMap =
                    mapOf(
                        "gen_ai.system" to metadata.genAISystem,
                        "finish_reason" to choice.finishReason().asString(),
                        "index" to choice.index().toString(),
                        "tool_calls" to toolCalls,
                    )

                eventData.putAll(tooCallMap)
            }

            observation.event(
                Observation.Event.of(GenAIObsAttributes.CHOICE, mapper.writeValueAsString(eventData)),
            )
        }
    }

    fun setAllObservationAttributes(
        observation: Observation,
        response: Response,
        params: ResponseCreateParams,
        metadata: InstrumentationMetadataInput,
        finishReason: String,
    ) {
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "chat")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SYSTEM, metadata.genAISystem)
        observation.lowCardinalityKeyValue(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_MODEL, response.model().asString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_PORT, metadata.modelProviderPort)
        observation.highCardinalityKeyValue(GenAIObsAttributes.RESPONSE_ID, response.id())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_FINISH_REASONS, finishReason)

        params.temperature().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TEMPERATURE, it.toString()) }
        params.maxOutputTokens().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_MAX_TOKENS, it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TOP_P, it.toString()) }

        response.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_INPUT_TOKENS, usage.inputTokens().toString())
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_OUTPUT_TOKENS, usage.outputTokens().toString())
        }
        setOutputType(observation, params)
    }

    fun setAllObservationAttributes(
        observation: Observation,
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        metadata: InstrumentationMetadataInput,
    ) {
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "chat")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SYSTEM, metadata.genAISystem)
        observation.lowCardinalityKeyValue(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_MODEL, chatCompletion.model())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_PORT, metadata.modelProviderPort)
        observation.highCardinalityKeyValue(GenAIObsAttributes.RESPONSE_ID, chatCompletion.id())

        params.temperature().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TEMPERATURE, it.toString()) }
        params.maxOutputTokens().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_MAX_TOKENS, it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TOP_P, it.toString()) }

        chatCompletion.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_INPUT_TOKENS, usage.promptTokens().toString())
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_OUTPUT_TOKENS, usage.completionTokens().toString())
        }

        setFinishReasons(observation, chatCompletion)
        setOutputType(observation, params)
    }

    private fun setOutputType(
        observation: Observation,
        params: ResponseCreateParams,
    ) {
        if (params.text().isPresent &&
            params
                .text()
                .get()
                .format()
                .isPresent
        ) {
            val responseFormatConfig =
                params
                    .text()
                    .get()
                    .format()
                    .get()
            val format =
                if (responseFormatConfig.isText()) {
                    responseFormatConfig
                        .asText()
                        ._type()
                        .toString()
                } else if (responseFormatConfig.isJsonObject()) {
                    responseFormatConfig
                        .asJsonObject()
                        ._type()
                        .toString()
                } else {
                    responseFormatConfig.asJsonSchema()._type().toString()
                }
            observation.lowCardinalityKeyValue(GenAIObsAttributes.OUTPUT_TYPE, format)
        }
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

    /**
     * Starts an observation for a client operation, optionally as a child of [parentObservation].
     */
    suspend fun startObservation(
        operationName: String,
        modelName: String,
        parentObservation: Observation? = null,
    ): Observation {
        val observation = Observation.createNotStarted("$operationName $modelName", observationRegistry)
        parentObservation?.let { observation.parentObservation(it) }
        observation.highCardinalityKeyValue(GenAIObsAttributes.SPAN_KIND, "client")
        observation.start()
        return observation
    }

    fun stopObservation(
        observation: Observation,
        response: Response,
        params: ResponseCreateParams,
        metadata: InstrumentationMetadataInput,
    ) {
        emitModelOutputEvents(observation, response, metadata)
        setAllObservationAttributes(observation, response, params, metadata, "stop")
        recordTokenUsage(metadata, response, params, "input")
        recordTokenUsage(metadata, response, params, "output")
        observation.stop()
    }

    fun <T> withClientObservation(
        operationName: String,
        modelName: String,
        block: (Observation) -> T,
    ): T = withClientObservation("$operationName $modelName", null, block)

    fun <T> withClientObservation(
        obsName: String,
        block: (Observation) -> T,
    ): T = withClientObservation(obsName, null, block)

    /**
     * Creates an observation named "$operationName $modelName" as a child of [parentObservation], if provided.
     */
    fun <T> withClientObservation(
        operationName: String,
        modelName: String,
        parentObservation: Observation?,
        block: (Observation) -> T,
    ): T = withClientObservation("$operationName $modelName", parentObservation, block)

    /**
     * Creates an observation named [obsName] as a child of [parentObservation], if provided.
     */
    fun <T> withClientObservation(
        obsName: String,
        parentObservation: Observation?,
        block: (Observation) -> T,
    ): T {
        val observation = Observation.createNotStarted(obsName, observationRegistry)
        parentObservation?.let { observation.parentObservation(it) }
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
        metadata: InstrumentationMetadataInput,
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
        metadata: InstrumentationMetadataInput,
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
        metadata: InstrumentationMetadataInput,
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
                ).serviceLevelObjectives(
                    1.0,
                    4.0,
                    16.0,
                    64.0,
                    256.0,
                    1024.0,
                    4096.0,
                    16384.0,
                    65536.0,
                    262144.0,
                    1048576.0,
                    4194304.0,
                    16777216.0,
                    67108864.0,
                )
        summaryBuilder.tag(GenAIObsAttributes.REQUEST_MODEL, requestModel)
        summaryBuilder.tag(GenAIObsAttributes.RESPONSE_MODEL, responseMode)
        summaryBuilder.tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
        summaryBuilder.tag(GenAIObsAttributes.SERVER_PORT, metadata.modelProviderPort)
        val summary = summaryBuilder.register(meterRegistry)
        summary.record(tokenCount.toDouble())
    }

    fun <T> withTimer(
        params: ResponseCreateParams,
        metadata: InstrumentationMetadataInput,
        block: () -> T,
    ): T {
        val timerBuilder =
            Timer
                .builder(GenAIObsAttributes.OPERATION_DURATION)
                .description("GenAI operation duration")
                .tags(GenAIObsAttributes.OPERATION_NAME, "chat", GenAIObsAttributes.SYSTEM, metadata.genAISystem)
                .tag(GenAIObsAttributes.REQUEST_MODEL, params.model().asString())
                .tag(GenAIObsAttributes.RESPONSE_MODEL, params.model().asString())
                .tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
                .tag(GenAIObsAttributes.SERVER_PORT, metadata.modelProviderPort)

        setSlo(timerBuilder)
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
        metadata: InstrumentationMetadataInput,
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
                .tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
                .tag(GenAIObsAttributes.SERVER_PORT, metadata.modelProviderPort)

        setSlo(timerBuilder)
        val timer = timerBuilder.register(meterRegistry)
        sample.stop(timer)
    }

    private fun setSlo(timerBuilder: Timer.Builder) {
        timerBuilder.serviceLevelObjectives(
            Duration.ofMillis((0.01 * 1000).toLong()),
            Duration.ofMillis((0.02 * 1000).toLong()),
            Duration.ofMillis((0.04 * 1000).toLong()),
            Duration.ofMillis((0.08 * 1000).toLong()),
            Duration.ofMillis((0.16 * 1000).toLong()),
            Duration.ofMillis((0.32 * 1000).toLong()),
            Duration.ofMillis((0.64 * 1000).toLong()),
            Duration.ofSeconds(1.28.toLong()),
            Duration.ofSeconds(2.56.toLong()),
            Duration.ofSeconds(5.12.toLong()),
            Duration.ofSeconds(10.24.toLong()),
            Duration.ofSeconds(20.48.toLong()),
            Duration.ofSeconds(40.96.toLong()),
            Duration.ofSeconds(81.92.toLong()),
        )
    }

    // ------------------------------------------------------------------------
    // File Operation Telemetry Methods
    // ------------------------------------------------------------------------

    /**
     * Starts an observation for a file operation.
     *
     * @param operationName The name of the file operation (e.g., "create", "read", "delete")
     * @param fileId The ID of the file
     * @param fileName The name of the file (optional)
     * @param purpose The purpose of the file (optional)
     * @return The created observation
     */
    suspend fun startFileOperation(
        operationName: String,
        fileId: String,
        fileName: String? = null,
        purpose: String? = null,
    ): Observation {
        val observation = startObservation("open-responses.file.$operationName", "")
        observation.lowCardinalityKeyValue(OpenResponsesObsAttributes.FILE_OPERATION, operationName)
        observation.highCardinalityKeyValue(OpenResponsesObsAttributes.FILE_ID, fileId)
        
        fileName?.let {
            observation.highCardinalityKeyValue(OpenResponsesObsAttributes.FILE_NAME, it)
        }
        
        purpose?.let {
            observation.lowCardinalityKeyValue(OpenResponsesObsAttributes.FILE_PURPOSE, it)
        }
        
        return observation
    }

    /**
     * Stops a file operation observation and records metrics.
     *
     * @param observation The observation to stop
     * @param fileSize The size of the file in bytes (optional)
     * @param success Whether the operation was successful
     */
    fun stopFileOperation(
        observation: Observation,
        fileSize: Long? = null,
        success: Boolean = true,
    ) {
        try {
            fileSize?.let {
                observation.highCardinalityKeyValue(OpenResponsesObsAttributes.FILE_SIZE, it.toString())
            }
            
            if (!success) {
                observation.error(RuntimeException("File operation failed"))
            }
        } finally {
            observation.stop()
        }
    }

    /**
     * Executes a file operation with observability.
     *
     * @param operationName The name of the file operation
     * @param fileId The ID of the file
     * @param fileName The name of the file (optional)
     * @param purpose The purpose of the file (optional)
     * @param block The operation to execute
     * @return The result of the operation
     */
    suspend fun <T> withFileOperation(
        operationName: String,
        fileId: String,
        fileName: String? = null,
        purpose: String? = null,
        block: suspend () -> T,
    ): T {
        val observation = startFileOperation(operationName, fileId, fileName, purpose)
        val timer =
            Timer
                .builder(OpenResponsesObsAttributes.FILE_OPERATION_DURATION)
                .description("File operation duration")
                .tags(OpenResponsesObsAttributes.FILE_OPERATION, operationName)
                .register(meterRegistry)
        val sample = Timer.start(meterRegistry)
        
        return try {
            val result = block()
            stopFileOperation(observation, success = true)
            result
        } catch (e: Exception) {
            stopFileOperation(observation, success = false)
            observation.error(e)
            throw e
        } finally {
            sample.stop(timer)
        }
    }
    
    // ------------------------------------------------------------------------
    // Vector Store Operation Telemetry Methods
    // ------------------------------------------------------------------------

    /**
     * Starts an observation for a vector store operation.
     *
     * @param operationName The name of the vector store operation (e.g., "create", "update", "delete")
     * @param vectorStoreId The ID of the vector store
     * @return The created observation
     */
    suspend fun startVectorStoreOperation(
        operationName: String,
        vectorStoreId: String,
    ): Observation {
        val observation = startObservation("open-responses.vector_store.$operationName", "")
        observation.lowCardinalityKeyValue(OpenResponsesObsAttributes.VECTOR_STORE_OPERATION, operationName)
        observation.highCardinalityKeyValue(OpenResponsesObsAttributes.VECTOR_STORE_ID, vectorStoreId)
        
        return observation
    }

    /**
     * Stops a vector store operation observation.
     *
     * @param observation The observation to stop
     * @param success Whether the operation was successful
     */
    fun stopVectorStoreOperation(
        observation: Observation,
        success: Boolean = true,
    ) {
        try {
            if (!success) {
                observation.error(RuntimeException("Vector store operation failed"))
            }
        } finally {
            observation.stop()
        }
    }

    /**
     * Executes a vector store operation with observability.
     *
     * @param operationName The name of the vector store operation
     * @param vectorStoreId The ID of the vector store
     * @param block The operation to execute
     * @return The result of the operation
     */
    suspend fun <T> withVectorStoreOperation(
        operationName: String,
        vectorStoreId: String,
        block: suspend () -> T,
    ): T {
        val observation = startVectorStoreOperation(operationName, vectorStoreId)
        val timer =
            Timer
                .builder(OpenResponsesObsAttributes.VECTOR_STORE_OPERATION_DURATION)
                .description("Vector store operation duration")
                .tags(OpenResponsesObsAttributes.VECTOR_STORE_OPERATION, operationName)
                .tags(OpenResponsesObsAttributes.VECTOR_STORE_ID, vectorStoreId)
                .register(meterRegistry)
        val sample = Timer.start(meterRegistry)
        
        return try {
            val result = block()
            stopVectorStoreOperation(observation, success = true)
            result
        } catch (e: Exception) {
            stopVectorStoreOperation(observation, success = false)
            observation.error(e)
            throw e
        } finally {
            sample.stop(timer)
        }
    }
    
    // ------------------------------------------------------------------------
    // Search Operation Telemetry Methods
    // ------------------------------------------------------------------------

    /**
     * Starts an observation for a search operation.
     *
     * @param operationName The name of the search operation (e.g., "semantic", "keyword")
     * @param vectorStoreId The ID of the vector store (optional)
     * @param query The search query
     * @return The created observation
     */
    suspend fun startSearchOperation(
        operationName: String,
        vectorStoreId: String? = null,
        query: String,
    ): Observation {
        val observation = startObservation("open-responses.search.$operationName", "")
        observation.lowCardinalityKeyValue(OpenResponsesObsAttributes.SEARCH_OPERATION, operationName)
        observation.highCardinalityKeyValue(OpenResponsesObsAttributes.SEARCH_QUERY, query)
        
        vectorStoreId?.let {
            observation.highCardinalityKeyValue(OpenResponsesObsAttributes.VECTOR_STORE_ID, it)
        }
        
        return observation
    }

    /**
     * Stops a search operation observation and records metrics.
     *
     * @param observation The observation to stop
     * @param resultsCount The number of search results (optional)
     * @param documentIds List of document IDs that were fetched (optional)
     * @param chunkIds List of chunk IDs that were fetched (optional)
     * @param scores List of similarity scores for the results (optional)
     * @param success Whether the operation was successful
     */
    fun stopSearchOperation(
        observation: Observation,
        resultsCount: Int? = null,
        documentIds: List<String>? = null,
        chunkIds: List<String>? = null,
        scores: List<Double>? = null,
        success: Boolean = true,
    ) {
        try {
            resultsCount?.let {
                observation.highCardinalityKeyValue(OpenResponsesObsAttributes.SEARCH_RESULTS_COUNT, it.toString())
            }
            
            documentIds?.let {
                if (it.isNotEmpty()) {
                    // Only record up to 10 document IDs to keep the metric cardinality under control
                    val limitedDocs = it.take(10)
                    observation.highCardinalityKeyValue(
                        OpenResponsesObsAttributes.SEARCH_DOCUMENT_IDS,
                        limitedDocs.joinToString(","),
                    )
                }
            }
            
            chunkIds?.let {
                if (it.isNotEmpty()) {
                    // Only record up to 10 chunk IDs to keep the metric cardinality under control
                    val limitedChunks = it.take(10)
                    observation.highCardinalityKeyValue(
                        OpenResponsesObsAttributes.SEARCH_CHUNK_IDS,
                        limitedChunks.joinToString(","),
                    )
                }
            }
            
            scores?.let {
                if (it.isNotEmpty()) {
                    // Record the top score (highest similarity)
                    val topScore = it.maxOrNull()
                    topScore?.let { score ->
                        observation.highCardinalityKeyValue(
                            OpenResponsesObsAttributes.SEARCH_TOP_SCORE,
                            score.toString(),
                        )
                    }
                    
                    // Record the average score
                    val avgScore = it.average()
                    observation.highCardinalityKeyValue(
                        OpenResponsesObsAttributes.SEARCH_AVG_SCORE,
                        avgScore.toString(),
                    )
                }
            }
            
            if (!success) {
                observation.error(RuntimeException("Search operation failed"))
            }
        } finally {
            observation.stop()
        }
    }

    fun recordTokenUsageForChatCompletion(
        metadata: InstrumentationMetadataInput,
        chatCompletionChunk: ChatCompletionChunk,
        tokenType: String,
        tokenCount: Long,
    ) {
        val responseModel = chatCompletionChunk.model()
        tokenUsage(metadata, responseModel, responseModel, tokenType, tokenCount)
    }

    /**
     * Records token usage for chat completions.
     */
    fun recordChatCompletionTokenUsage(
        metadata: InstrumentationMetadataInput,
        chatCompletion: ChatCompletion,
        params: ChatCompletionCreateParams,
        tokenType: String,
        tokenCount: Long,
    ) {
        val requestModel = params.model().toString()
        val responseModel = chatCompletion.model()
        tokenUsage(metadata, responseModel, requestModel, tokenType, tokenCount)
    }

    /**
     * Records token usage for chat completion streaming chunks.
     */
    fun recordChatCompletionChunkTokenUsage(
        metadata: InstrumentationMetadataInput,
        chatCompletionChunk: ChatCompletionChunk,
        tokenType: String,
        tokenCount: Long,
    ) {
        val responseModel = chatCompletionChunk.model()
        tokenUsage(metadata, responseModel, responseModel, tokenType, tokenCount)
    }

    /**
     * Creates a timer for chat completion operations.
     */
    fun <T> withChatCompletionTimer(
        params: ChatCompletionCreateParams,
        metadata: InstrumentationMetadataInput,
        block: () -> T,
    ): T {
        val timerBuilder =
            Timer
                .builder(GenAIObsAttributes.OPERATION_DURATION)
                .description("GenAI operation duration")
                .tags(GenAIObsAttributes.OPERATION_NAME, "chat", GenAIObsAttributes.SYSTEM, metadata.genAISystem)
                .tag(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
                .tag(GenAIObsAttributes.RESPONSE_MODEL, params.model().toString())
                .tag(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
        setSlo(timerBuilder)
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

    /**
     * Sets all observation attributes for a chat completion.
     */
    fun setChatCompletionObservationAttributes(
        observation: Observation,
        chatCompletion: ChatCompletion,
        params: ChatCompletionCreateParams,
        metadata: InstrumentationMetadataInput,
    ) {
        observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "chat")
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SYSTEM, metadata.genAISystem)
        observation.lowCardinalityKeyValue(GenAIObsAttributes.REQUEST_MODEL, params.model().toString())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.RESPONSE_MODEL, chatCompletion.model())
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_ADDRESS, metadata.modelProviderAddress)
        observation.lowCardinalityKeyValue(GenAIObsAttributes.SERVER_PORT, metadata.modelProviderPort)
        observation.highCardinalityKeyValue(GenAIObsAttributes.RESPONSE_ID, chatCompletion.id())

        params.temperature().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TEMPERATURE, it.toString()) }
        params.maxCompletionTokens().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_MAX_TOKENS, it.toString()) }
        params.topP().ifPresent { observation.highCardinalityKeyValue(GenAIObsAttributes.REQUEST_TOP_P, it.toString()) }

        chatCompletion.usage().ifPresent { usage ->
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_INPUT_TOKENS, usage.promptTokens().toString())
            observation.highCardinalityKeyValue(GenAIObsAttributes.USAGE_OUTPUT_TOKENS, usage.completionTokens().toString())
        }

        setChatCompletionFinishReasons(observation, chatCompletion)
        setOutputType(observation, params)
    }

    private fun setOutputType(
        observation: Observation,
        params: ChatCompletionCreateParams,
    ) {
        if (params.responseFormat().isPresent &&
            params
                .responseFormat()
                .isPresent
        ) {
            val responseFormatConfig =
                params
                    .responseFormat()
                    .get()
            val format =
                if (responseFormatConfig.isText()) {
                    responseFormatConfig
                        .asText()
                        ._type()
                        .toString()
                } else if (responseFormatConfig.isJsonObject()) {
                    responseFormatConfig
                        .asJsonObject()
                        ._type()
                        .toString()
                } else {
                    responseFormatConfig.asJsonSchema()._type().toString()
                }
            observation.lowCardinalityKeyValue(GenAIObsAttributes.OUTPUT_TYPE, format)
        }
    }

    /**
     * Sets finish reasons for a chat completion observation.
     */
    private fun setChatCompletionFinishReasons(
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

    private fun messageContent(content: String) = if (captureMessageContent) content else ""

    private fun putIfNotEmpty(
        map: MutableMap<String, String>,
        key: String,
        value: String?,
    ) {
        if (!value.isNullOrEmpty()) {
            map[key] = value
        }
    }
}
