package com.masaic.openai.tool

enum class ToolHosting {
    MASAIC_MANAGED,
    SELF_HOSTED
}

enum class ToolProtocol {
    MCP
}

open class ToolDefinition(
    open val id: String,
    open val protocol: ToolProtocol,
    open val hosting: ToolHosting,
    open val name: String,
    open val description: String,
)

