package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.AgenticSeachTool
import ai.masaic.openresponses.api.model.FileSearchTool
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.FunctionTool
import ai.masaic.openresponses.api.model.Tool
import ai.masaic.openresponses.api.model.UserLocation
import ai.masaic.openresponses.api.model.WebSearchTool
import com.fasterxml.jackson.databind.ObjectMapper
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
    REMOTE
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
    override fun getModel(): String = params.model().toString()

    override fun getDefaultTemperature(): Double? = params.temperature().getOrDefault(1.0)

    override fun getTools(): List<Tool> =
        params.tools().getOrElse { emptyList<com.openai.models.responses.Tool>() }.mapNotNull { it ->
            // Use mapNotNull to handle potential nulls from conversion
            if (it.isFileSearch()) {
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
                    alias = it.asFileSearch()._additionalProperties()["alias"]?.toString(),
                )
            } else if (it.isWebSearch()) {
                // Assuming WebSearch in Response params maps to AgenticSearchTool config
                val props = it.asWebSearch()._additionalProperties()
                AgenticSeachTool(
                    type = "agentic_search", // Map type correctly
                    vectorStoreIds = props["vector_store_ids"]?.asArray()?.getOrDefault(emptyList())?.map { it.toString() },
                    filters = props["filters"]?.convert(Filter::class.java),
                    maxNumResults = props["max_num_results"]?.toString()?.toIntOrNull() ?: 20,
                    maxIterations = props["max_iterations"]?.toString()?.toIntOrNull() ?: 5,
                    alias = props["alias"]?.toString(),
                    enablePresencePenaltyTuning = props["enable_presence_penalty_tuning"]?.toString()?.toBooleanStrictOrNull(),
                    enableFrequencyPenaltyTuning = props["enable_frequency_penalty_tuning"]?.toString()?.toBooleanStrictOrNull(),
                    enableTemperatureTuning = props["enable_temperature_tuning"]?.toString()?.toBooleanStrictOrNull(),
                    enableTopPTuning = props["enable_top_p_tuning"]?.toString()?.toBooleanStrictOrNull(),
                )
            } else if (it.isFunction()) {
                FunctionTool(
                    type = "function",
                    name = it.asFunction().name(),
                    description = it.asFunction().description().getOrNull(),
                    parameters = objectMapper.convertValue(it.asFunction().parameters(), Map::class.java) as MutableMap<String, Any>,
                    strict = true, // Assuming strict true based on previous FunctionTool definition
                    // alias = it.asFunction()._additionalProperties()["alias"]?.toString(), // If function can have alias
                )
            } else {
                null // Handle other types or return null if not applicable
            }
        }

    override fun <T : Tool> getSpecificToolConfig(
        toolName: String,
        toolClass: Class<T>,
    ): T? = getTools().find { it.type == toolName && toolClass.isInstance(it) } as? T
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
            // Chat tools are functions, check the function name for type
            val toolFunction = rawTool.function()
            val toolName = toolFunction.name()
            val props =
                try {
                    toolFunction.parameters().let { objectMapper.convertValue(it, Map::class.java) } ?: emptyMap<String, Any>()
                } catch (e: Exception) {
                    // Handle cases where parameters are not a valid map or missing
                    mapOf<String, Any>() // Default to empty map on error
                }

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
                        vectorStoreIds = additionalProps["vector_store_ids"]?.asArray()?.getOrDefault(emptyList())?.map { it.toString() }, // Extract from parameters map
                        filters = additionalProps["filters"], // Extract from parameters map
                        maxNumResults = additionalProps["max_num_results"]?.toString()?.toIntOrNull() ?: 20,
                        alias = additionalProps["alias"]?.toString(),
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
                        // Extract tuning flags from parameters map
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
                    )
                }
                "web_search" -> {
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
                                objectMapper.convertValue(
                                    it,
                                    UserLocation::class.java,
                                )
                            },
                        searchContextSize = additionalProps["search_context_size"]?.toString() ?: "medium",
                        domains = additionalProps["domains"] as? List<String> ?: emptyList(), // Extract domains if present
                        // alias = props["alias"]?.toString(),
                    )
                }
                // Default case for other function tools
                else ->
                    FunctionTool(
                        type = "function",
                        name = toolName,
                        description = toolFunction.description().getOrNull(),
                        parameters = props as? MutableMap<String, Any> ?: mutableMapOf(), // Use extracted props
                        strict = true, // Assuming strict true for function tools
                        // alias = props["alias"]?.toString(),
                    )
            }
        }

    override fun <T : Tool> getSpecificToolConfig(
        toolName: String,
        toolClass: Class<T>,
    ): T? = getTools().find { it.type == toolName && toolClass.isInstance(it) } as? T
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
