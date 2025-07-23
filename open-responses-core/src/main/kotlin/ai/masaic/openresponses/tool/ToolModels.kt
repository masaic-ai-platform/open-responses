package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonString
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.responses.ResponseCreateParams
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

/**
 * Defines the hosting options for tools.
 *
 * This enum represents the different hosting configurations available for tools
 * in the system.
 */
enum class ToolHosting {
    /** Tool is hosted and managed by Masaic */
    MASAIC_MANAGED,
    REMOTE,
}

/**
 * Defines the communication protocols for tools.
 *
 * This enum represents the supported protocols that tools can use
 * for communication with the system.
 */
enum class ToolProtocol {
    /** Masaic Communication Protocol */
    MCP,
    NATIVE,
}

/**
 * Base class that defines the structure of a tool.
 *
 * @property id Unique identifier for the tool
 * @property protocol Communication protocol used by the tool
 * @property hosting Hosting configuration for the tool
 * @property name Human-readable name of the tool
 * @property description Detailed description of what the tool does
 */
open class ToolDefinition(
    open val id: String,
    open val protocol: ToolProtocol,
    open val hosting: ToolHosting,
    open val name: String,
    open val description: String,
)

/**
 * Defines a Native tool with its parameters.
 *
 * Extends the base [ToolDefinition] class with Native properties.
 *
 * @property id Unique identifier for the tool
 * @property protocol Communication protocol used by the tool
 * @property hosting Hosting configuration for the tool
 * @property name Human-readable name of the tool
 * @property description Detailed description of what the tool does
 * @property parameters JSON schema defining the parameters accepted by the tool
 */
data class NativeToolDefinition(
    override val id: String = UUID.randomUUID().toString(),
    override val protocol: ToolProtocol = ToolProtocol.NATIVE,
    override val hosting: ToolHosting = ToolHosting.MASAIC_MANAGED,
    override val name: String,
    override val description: String,
    val parameters: MutableMap<String, Any>,
) : ToolDefinition(id, protocol, hosting, name, description) {
    companion object {
        fun toFunctionTool(toolDefinition: NativeToolDefinition): FunctionTool =
            FunctionTool(
                description = toolDefinition.description,
                name = toolDefinition.name,
                parameters = toolDefinition.parameters,
                strict = true,
            )

        fun toChatCompletionTool(
            toolDefinition: NativeToolDefinition,
            objectMapper: ObjectMapper,
        ): ChatCompletionTool =
            ChatCompletionTool
                .builder()
                .type(JsonValue.from("function"))
                .function(
                    FunctionDefinition
                        .builder()
                        .name(toolDefinition.name)
                        .description(toolDefinition.description)
                        .parameters(objectMapper.convertValue(toolDefinition.parameters, FunctionParameters::class.java))
                        .build(),
                ).build()
    }
}

/**
 * Context for tool execution that includes alias mappings and original request parameters.
 *
 * @property aliasMap Mapping of alias names to actual tool names
 * @property originalParams The original request parameters
 */
data class ToolRequestContext(
    val aliasMap: Map<String, String> = emptyMap(),
    val originalParams: ResponseCreateParams? = null,
)

/**
 * Context for tool execution that includes alias mappings and original request parameters.
 *
 * @property aliasMap Mapping of alias names to actual tool names
 * @property originalParams The original request parameters
 */
data class CompletionToolRequestContext(
    val aliasMap: Map<String, String> = emptyMap(),
    val originalParams: ChatCompletionCreateParams? = null,
)

/**
 * Unified context for tool execution, abstracting over Response/Completion flows.
 *
 * @property aliasMap Mapping of alias names to actual tool names.
 */
data class UnifiedToolContext(
    val aliasMap: Map<String, String> = emptyMap(),
    // Add any other common context properties if needed in the future
)

/**
 * Interface to provide unified access to tool parameters from different request types.
 */
interface ToolParamsAccessor {
    fun getModel(): String

    fun getTools(): List<Tool> // Returns the list of common Tool models

    fun getDefaultTemperature(): Double?

    fun <T : Tool> getSpecificToolConfig(
        toolName: String,
        toolClass: Class<T>,
    ): T?
}

/**
 * Adapter for ResponseCreateParams to access tool parameters uniformly.
 */
class ResponseParamsAdapter(
    internal val params: ResponseCreateParams,
    val objectMapper: ObjectMapper,
) : ToolParamsAccessor {
    override fun getModel(): String = if (params.model().isString()) params.model().string().get() else params.model().asChat().toString()

    override fun getDefaultTemperature(): Double? = params.temperature().getOrDefault(1.0)

    override fun getTools(): List<Tool> =
        params.tools().getOrElse { emptyList<com.openai.models.responses.Tool>() }.mapNotNull { it ->
            // Use mapNotNull to handle potential nulls from conversion
            if (it.isFileSearch()) {
                val props = it.asFileSearch()._additionalProperties()
                FileSearchTool(
                    type = "file_search",
                    vectorStoreIds = it.asFileSearch().vectorStoreIds(),
                    filters =
                        it
                            .asFileSearch()
                            .filters()
                            .orElse(null)
                            ?._json()
                            ?.get(),
                    maxNumResults =
                        it
                            .asFileSearch()
                            .maxNumResults()
                            .orElse(20)
                            .toInt(),
                    alias = props["alias"]?.toString(),
                    modelInfo = extractModelInfo(props),
                )
            } else if (it.isWebSearch()) {
                if (it.asWebSearch().type().toString() == "agentic_search") {
                    val props = it.asWebSearch()._additionalProperties()
                    AgenticSeachTool(
                        type = "agentic_search",
                        vectorStoreIds =
                            props["vector_store_ids"]
                                ?.asArray()
                                ?.getOrDefault(emptyList())
                                ?.map { it.toString() },
                        filters = props["filters"]?.convert(Filter::class.java),
                        maxNumResults = props["max_num_results"]?.toString()?.toIntOrNull() ?: 20,
                        maxIterations = props["max_iterations"]?.toString()?.toIntOrNull() ?: 5,
                        alias = props["alias"]?.toString(),
                        enablePresencePenaltyTuning =
                            props["enable_presence_penalty_tuning"]
                                ?.toString()
                                ?.toBooleanStrictOrNull(),
                        enableFrequencyPenaltyTuning =
                            props["enable_frequency_penalty_tuning"]
                                ?.toString()
                                ?.toBooleanStrictOrNull(),
                        enableTemperatureTuning =
                            props["enable_temperature_tuning"]
                                ?.toString()
                                ?.toBooleanStrictOrNull(),
                        enableTopPTuning = props["enable_top_p_tuning"]?.toString()?.toBooleanStrictOrNull(),
                        modelInfo = extractModelInfo(props),
                    )
                } else {
                    throw IllegalArgumentException("Unsupported type of tool: ${it.asWebSearch().type()}")
                }
            } else if (it.isImageGeneration()) {
                val request = it.asImageGeneration()
                val props = it.asImageGeneration()._additionalProperties()
                ImageGenerationTool(
                    background = request.background().getOrNull().toString(),
                    inputImageMask =
                        request.inputImageMask().getOrNull()?.let { maskJson ->
                            try {
                                objectMapper.convertValue(maskJson, InputImageMask::class.java)
                            } catch (e: Exception) {
                                null
                            }
                        },
                    model = request.model().getOrNull().toString(),
                    moderation = request.moderation().getOrNull().toString(),
                    outputCompression = request.outputCompression().getOrNull()?.toInt(),
                    outputFormat = request.outputFormat().getOrNull().toString(),
                    partialImages = request.partialImages().getOrNull()?.toInt(),
                    quality = request.quality().getOrNull()?.toString(),
                    size = request.size().getOrNull().toString(),
                    responseFormat = props["response_format"]?.asString()?.getOrNull(),
                    style = props["style"]?.asString()?.getOrNull(),
                    user = props["user"]?.asString()?.getOrNull(),
                    modelProviderKey = props["model_provider_key"]?.asString()?.getOrNull(),
                )
            } else if (it.isFunction()) {
                val func = it.asFunction()
                val funcName = func.name()
                val additionalProps = func._additionalProperties()

                when (funcName) {
                    "image_generation" -> {
                        ImageGenerationTool(
                            background = additionalProps["background"]?.asString()?.getOrNull(),
                            inputImageMask =
                                additionalProps["input_image_mask"]?.let { maskJson ->
                                    try {
                                        objectMapper.convertValue(maskJson.asObject().get(), InputImageMask::class.java)
                                    } catch (e: Exception) {
                                        null
                                    }
                                },
                            model = additionalProps["model"]?.asString()?.getOrNull().toString(),
                            moderation = additionalProps["moderation"]?.asString()?.getOrNull(),
                            outputCompression = additionalProps["output_compression"]?.asNumber()?.getOrNull()?.toInt(),
                            outputFormat = additionalProps["output_format"]?.asString()?.getOrNull(),
                            partialImages = additionalProps["partial_images"]?.asNumber()?.getOrNull()?.toInt(),
                            quality = additionalProps["quality"]?.asString()?.getOrNull(),
                            size = additionalProps["size"]?.asString()?.getOrNull(),
                            n = additionalProps["n"]?.asNumber()?.getOrNull()?.toInt(),
                            responseFormat = additionalProps["response_format"]?.asString()?.getOrNull(),
                            style = additionalProps["style"]?.asString()?.getOrNull(),
                            user = additionalProps["user"]?.asString()?.getOrNull(),
                            modelProviderKey = additionalProps["model_provider_key"]?.asString()?.getOrNull(),
                        )
                    }
                    else -> {
                        FunctionTool(
                            type = "function",
                            name = funcName,
                            description = func.description().getOrNull(),
                            parameters = objectMapper.convertValue(func.parameters(), Map::class.java) as MutableMap<String, Any>,
                            strict = true,
                        )
                    }
                }
            } else {
                null
            }
        }

    override fun <T : Tool> getSpecificToolConfig(
        toolName: String,
        toolClass: Class<T>,
    ): T? = getTools().find { it.type == toolName && toolClass.isInstance(it) } as? T

    private fun extractModelInfo(props: Map<String, JsonValue>): ModelInfo? {
        val modelInfoObject = props["modelInfo"]?.asObject()?.getOrNull()
        return if (modelInfoObject == null) {
            null
        } else {
            val bearerToken = (modelInfoObject.getValue("bearerToken") as? JsonString)?.value
            val model = (modelInfoObject.getValue("model") as? JsonString)?.value
            return ModelInfo.fromApiKey(bearerToken, model)
        }
    }
}

/**
 * Adapter for ChatCompletionCreateParams to access tool parameters uniformly.
 */
class ChatCompletionParamsAdapter(
    internal val params: ChatCompletionCreateParams,
    private val objectMapper: ObjectMapper,
) : ToolParamsAccessor {
    override fun getModel(): String = params.model().toString() // Assuming model is JsonValue or String

    override fun getDefaultTemperature(): Double? = params.temperature().getOrNull()

    override fun getTools(): List<Tool> =
        params.tools().orElse(emptyList()).mapNotNull { rawTool ->
            val toolFunction = rawTool.function()
            val toolName = toolFunction.name()

            when (toolName) {
                "file_search" -> {
                    val additionalProps =
                        params
                            .tools()
                            .getOrDefault(emptyList())
                            .first { it.function().name() == "file_search" }
                            ._additionalProperties()
                    FileSearchTool(
                        type = "file_search",
                        vectorStoreIds = additionalProps["vector_store_ids"]?.asArray()?.getOrDefault(emptyList())?.map { it.toString() },
                        filters = additionalProps["filters"],
                        maxNumResults = additionalProps["max_num_results"]?.toString()?.toIntOrNull() ?: 20,
                        alias = additionalProps["alias"]?.toString(),
                        modelInfo = extractModelInfo(additionalProps),
                    )
                }
                "agentic_search" -> {
                    val additionalProps =
                        params
                            .tools()
                            .getOrDefault(emptyList())
                            .first { it.function().name() == "agentic_search" }
                            ._additionalProperties()
                    AgenticSeachTool(
                        type = "agentic_search",
                        vectorStoreIds = additionalProps["vector_store_ids"]?.asArray()?.getOrDefault(emptyList())?.map { it.toString() },
                        filters = additionalProps["filters"],
                        maxNumResults = additionalProps["max_num_results"]?.toString()?.toIntOrNull() ?: 20,
                        maxIterations = additionalProps["max_iterations"]?.toString()?.toIntOrNull() ?: 5,
                        alias = additionalProps["alias"]?.toString(),
                        enablePresencePenaltyTuning =
                            additionalProps["enable_presence_penalty_tuning"]
                                ?.toString()
                                ?.toBooleanStrictOrNull(),
                        enableFrequencyPenaltyTuning =
                            additionalProps["enable_frequency_penalty_tuning"]
                                ?.toString()
                                ?.toBooleanStrictOrNull(),
                        enableTemperatureTuning =
                            additionalProps["enable_temperature_tuning"]
                                ?.toString()
                                ?.toBooleanStrictOrNull(),
                        enableTopPTuning = additionalProps["enable_top_p_tuning"]?.toString()?.toBooleanStrictOrNull(),
                        modelInfo = extractModelInfo(additionalProps),
                    )
                }
                "web_search_preview" -> {
                    val additionalProps =
                        params
                            .tools()
                            .getOrDefault(emptyList())
                            .first { it.function().name() == "web_search_preview" }
                            ._additionalProperties()
                    WebSearchTool(
                        type = "web_search_preview",
                        userLocation =
                            additionalProps["user_location"]?.let {
                                try {
                                    objectMapper.convertValue(it.asObject().get(), UserLocation::class.java)
                                } catch (e: Exception) {
                                    null
                                }
                            },
                        searchContextSize = additionalProps["search_context_size"]?.toString() ?: "medium",
                        domains = additionalProps["domains"]?.asArray()?.getOrDefault(emptyList())?.mapNotNull { it.asString()?.getOrNull() } ?: emptyList(),
                    )
                }
                "image_generation" -> {
                    val additionalProps =
                        params
                            .tools()
                            .getOrDefault(emptyList())
                            .first { it.function().name() == "image_generation" }
                            ._additionalProperties()
                    ImageGenerationTool(
                        type = "image_generation",
                        background = additionalProps["background"]?.asString()?.getOrNull(),
                        inputImageMask =
                            additionalProps["input_image_mask"]?.let { maskJson ->
                                try {
                                    objectMapper.convertValue(maskJson.asObject().get(), InputImageMask::class.java)
                                } catch (e: Exception) {
                                    null
                                }
                            },
                        model = additionalProps["model"]?.asString()?.getOrNull() ?: "gpt-image-1",
                        moderation = additionalProps["moderation"]?.asString()?.getOrNull(),
                        outputCompression = additionalProps["output_compression"]?.asNumber()?.getOrNull()?.toInt(),
                        outputFormat = additionalProps["output_format"]?.asString()?.getOrNull(),
                        partialImages = additionalProps["partial_images"]?.asNumber()?.getOrNull()?.toInt(),
                        quality = additionalProps["quality"]?.asString()?.getOrNull(),
                        size = additionalProps["size"]?.asString()?.getOrNull(),
                        n = additionalProps["n"]?.asNumber()?.getOrNull()?.toInt(),
                        responseFormat = additionalProps["response_format"]?.asString()?.getOrNull(),
                        style = additionalProps["style"]?.asString()?.getOrNull(),
                        user = additionalProps["user"]?.asString()?.getOrNull(),
                        modelProviderKey = additionalProps["model_provider_key"]?.asString()?.getOrNull(),
                    )
                }
                else -> {
                    val functionParams =
                        try {
                            toolFunction.parameters().let { objectMapper.convertValue(it, Map::class.java) } ?: emptyMap<String, Any>()
                        } catch (e: Exception) {
                            mapOf<String, Any>()
                        }
                    FunctionTool(
                        type = "function",
                        name = toolName,
                        description = toolFunction.description().getOrNull(),
                        parameters = functionParams as? MutableMap<String, Any> ?: mutableMapOf(),
                        strict = true,
                    )
                }
            }
        }

    override fun <T : Tool> getSpecificToolConfig(
        toolName: String,
        toolClass: Class<T>,
    ): T? = getTools().find { it.type == toolName && toolClass.isInstance(it) } as? T

    private fun extractModelInfo(props: Map<String, JsonValue>): ModelInfo? {
        val modelInfoObject = props["modelInfo"]?.asObject()?.getOrNull()
        return if (modelInfoObject == null) {
            null
        } else {
            val mInfo: ModelInfo = objectMapper.readValue(modelInfoObject.toString())
            ModelInfo.fromApiKey(mInfo.bearerToken, mInfo.model)
        }
    }
}

/**
 * Metadata about a tool.
 */
@Serializable
data class ToolMetadata(
    val id: String,
    val name: String,
    val description: String,
    val protocol: ToolProtocol = ToolProtocol.NATIVE,
    val hosting: ToolHosting = ToolHosting.MASAIC_MANAGED,
)
