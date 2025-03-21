package com.masaic.openai.tool

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
    SELF_HOSTED
}

/**
 * Defines the communication protocols for tools.
 * 
 * This enum represents the supported protocols that tools can use
 * for communication with the system.
 */
enum class ToolProtocol {
    /** Masaic Communication Protocol */
    MCP
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

