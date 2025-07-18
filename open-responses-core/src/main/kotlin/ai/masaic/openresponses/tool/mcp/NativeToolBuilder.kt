package ai.masaic.openresponses.tool.mcp

import ai.masaic.openresponses.tool.NativeToolDefinition

/**
 * Main DSL entry point.
 *
 * Usage:
 *   val tool = nativeTool {
 *       name("FunctionRequirementGatherer")
 *       description("Gathers user requirements for a function to be generated and produces a string output.")
 *       parameters {
 *           property(
 *               name = "userMessage",
 *               type = "string",
 *               description = "String parameter to feed in the user requirement message for the function.",
 *               required = true
 *           )
 *           property(
 *               name = "context",
 *               type = "string",
 *               description = "Chain of user and assistant messages that have happened so far in requirements-gathering.",
 *               required = true
 *           )
 *           additionalProperties = false   // default is false; change if you need it
 *       }
 *   }
 */
fun nativeToolDefinition(init: NativeToolBuilder.() -> Unit): NativeToolDefinition = NativeToolBuilder().apply(init).build()

/**
 * Builder for NativeToolDefinition.
 */
class NativeToolBuilder {
    private var name: String? = null
    private var description: String? = null
    private var parameters: MutableMap<String, Any>? = null

    fun name(value: String) = apply { name = value }

    fun description(value: String) = apply { description = value }

    fun parameters(init: ParameterSchemaBuilder.() -> Unit) =
        apply {
            parameters = ParameterSchemaBuilder().apply(init).build()
        }

    fun build(): NativeToolDefinition =
        NativeToolDefinition(
            name = requireNotNull(name) { "Tool name is required." },
            description = requireNotNull(description) { "Tool description is required." },
            parameters =
                parameters ?: mutableMapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                    "required" to emptyList<String>(),
                    "additionalProperties" to false,
                ),
        )
}

/**
 * Builder for the JSON-schema-like `parameters` block.
 */
class ParameterSchemaBuilder {
    private val properties = mutableMapOf<String, Map<String, String>>()
    private val requiredProps = mutableSetOf<String>()
    var additionalProperties: Boolean = false

    /**
     * Define a single property.
     */
    fun property(
        name: String,
        type: String,
        description: String,
        required: Boolean = false,
    ) = apply {
        properties[name] =
            mapOf(
                "type" to type,
                "description" to description,
            )
        if (required) requiredProps += name
    }

    /**
     * Produce the parameters map in the exact shape
     * expected by OpenAI function-calling.
     */
    fun build(): MutableMap<String, Any> =
        mutableMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to requiredProps.toList(),
            "additionalProperties" to additionalProperties,
        )
}
