package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.FunctionTool
import java.util.*

/**
 * Defines the hosting options for tools.
 *
 * This enum represents the different hosting configurations available for tools
 * in the system.
 */
enum class ToolHosting {
    /** Tool is hosted and managed by Masaic */
    MASAIC_MANAGED,

    /** Tool is self-hosted by the client */
    SELF_HOSTED,
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
    }
}
