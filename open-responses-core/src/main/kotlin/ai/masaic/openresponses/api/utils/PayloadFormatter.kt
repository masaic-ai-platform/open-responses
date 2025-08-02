package ai.masaic.openresponses.api.utils

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.tool.ToolProtocol
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonValue
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseStreamEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

@Component
class PayloadFormatter(
    private val toolService: ToolService,
    private val mapper: ObjectMapper,
) {
    internal suspend fun formatResponseRequest(request: CreateResponseRequest) {
        request.tools = updateToolsInRequest(request.tools)
    }

    internal suspend fun formatCompletionRequest(request: CreateCompletionRequest) {
        request.tools = updateToolsInCompletionRequest(request.tools)
    }

    /**
     * Updates the tools in the request with proper tool definitions from the tool service.
     *
     * @param tools The original list of tools in the request
     * @return The updated list of tools
     */
    private suspend fun updateToolsInRequest(tools: List<Tool>?): MutableList<Tool>? {
        val updatedTools = mutableListOf<Tool>()
        tools?.forEach {
            when (it) {
                is MasaicManagedTool -> {
                    val tool =
                        toolService.getFunctionTool(it.type) ?: throw ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Define tool ${it.type} properly",
                        )
                    updatedTools.add(tool)
                }

                is MCPTool -> {
                    val mcpToolFunctions = toolService.getRemoteMcpTools(it)
                    if (mcpToolFunctions.isEmpty()) throw IllegalStateException("No MCP tools found for ${it.serverLabel}, ${it.serverUrl}")
                    updatedTools.addAll(mcpToolFunctions)
                }

                else -> {
                    updatedTools.add(it)
                }
            }
        }
        return updatedTools
    }

    /**
     * Updates the tools in the request with proper tool definitions from the tool service.
     *
     * @param tools The original list of tools in the request
     * @return The updated list of tools
     */
    private suspend fun updateToolsInCompletionRequest(
        tools: List<Map<String, Any>>?,
    ): List<Map<String, Any>>? {
        if (tools == null) return null

        // We want to end up back in List<Map<String,Any>>
        val listType = object : TypeReference<List<Map<String, Any>>>() {}

        // Process each tool, producing 0..n Map<String,Any> entries per input
        val processed: List<Map<String, Any>> =
            tools.flatMap { tool ->
                when (tool["type"]?.toString()) {
                    "function" -> {
                        // leave functions untouched
                        listOf(tool)
                    }
                    "mcp" -> {
                        // read into your MCPTool, fetch remote tools, then map back to Map
                        val mcpObj =
                            mapper.readValue<MCPTool>(
                                mapper.writeValueAsString(tool),
                            )
                        toolService
                            .getRemoteMcpToolsForChatCompletion(mcpObj)
                            .map { mapper.convertValue(it, object : TypeReference<Map<String, Any>>() {}) }
                    }
                    else -> {
                        // non-function, non-MCP: either a known tool or fallback
                        val type = tool["type"].toString()
                        val available = toolService.getAvailableTool(type)
                        if (available != null) {
                            // build a ChatCompletionTool and convert it back to Map
                            val ccTool =
                                toolService.getChatCompletionTool(type)
                                    ?: throw ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Define tool $type properly",
                                    )
                            val built =
                                ccTool
                                    .toBuilder()
                                    .putAllAdditionalProperties(tool.mapValues { JsonValue.from(it.value) })
                                    .build()
                            listOf(
                                mapper.convertValue(built, object : TypeReference<Map<String, Any>>() {}),
                            )
                        } else {
                            // unknown type: just pass the raw map through
                            listOf(tool)
                        }
                    }
                }
            }

        // Finally convert our List<Map<String,Any>> to the declared return type
        return mapper.convertValue(processed, listType)
    }

    /**
     * Updates the tools in the response to replace function tools with Masaic managed tools.
     *
     * @param response The response to update
     * @return Updated JSON representation of the response
     */
    internal fun formatResponse(response: Response): JsonNode {
        // Convert the Response object to a mutable JSON tree
        val rootNode = mapper.valueToTree<JsonNode>(response) as ObjectNode
        return formatResponseNode(rootNode)
    }

    private fun updateDoubleFormat(rootNode: ObjectNode) {
        val createdAtNode = rootNode.get("created_at")
        if (createdAtNode?.isDouble == true) {
            rootNode.put("created_at", BigDecimal.valueOf(createdAtNode.doubleValue()))
        }
    }

    internal fun formatResponseStreamEvent(event: ResponseStreamEvent): JsonNode {
        if (event.isCompleted()) {
            val responseNode = mapper.valueToTree<JsonNode>(event.asCompleted().response()) as ObjectNode
            formatResponseNode(responseNode)
            val rootNode = mapper.valueToTree<JsonNode>(event) as ObjectNode
            rootNode.replace("response", responseNode)
            return rootNode
        } else if (event.isCreated()) {
            val responseNode = formatResponseNode(mapper.valueToTree<JsonNode>(event.asCreated().response()) as ObjectNode)
            val rootNode = mapper.valueToTree<JsonNode>(event) as ObjectNode
            rootNode.replace("response", responseNode)
            return rootNode
        } else if (event.isInProgress()) {
            val responseNode = mapper.valueToTree<JsonNode>(event.asInProgress().response()) as ObjectNode
            formatResponseNode(responseNode)
            val rootNode = mapper.valueToTree<JsonNode>(event) as ObjectNode
            rootNode.replace("response", responseNode)
            return rootNode
        } else if (event.isFailed()) {
            val responseNode = mapper.valueToTree<JsonNode>(event.asFailed().response()) as ObjectNode
            formatResponseNode(responseNode)
            val rootNode = mapper.valueToTree<JsonNode>(event) as ObjectNode
            rootNode.replace("response", responseNode)
            return rootNode
        }

        val rootNode = mapper.valueToTree<JsonNode>(event) as ObjectNode
        updateDoubleFormat(rootNode)
        return rootNode
    }

    private fun formatResponseNode(rootNode: ObjectNode): ObjectNode {
        updateToolsInResponseJson(rootNode)
        updateDoubleFormat(rootNode)
        return rootNode
    }

    /**
     * Updates the tools array in the response JSON.
     *
     * @param rootNode The root JSON node of the response
     */
    private fun updateToolsInResponseJson(rootNode: ObjectNode) {
        // Get the "tools" array node (if present)
        val toolsNode = rootNode.get("tools") as? ArrayNode ?: return

        // Iterate over each tool node in the array
        for (i in 0 until toolsNode.size()) {
            val toolNode = toolsNode.get(i) as? ObjectNode ?: continue

            // Check if this tool is a function tool by looking at its "type" field.
            if (isToolNodeAFunction(toolNode)) {
                replaceFunctionToolWithMasaicTool(toolsNode, toolNode, i)
            }
        }
    }

    /**
     * Checks if the given tool node is a function tool.
     *
     * @param toolNode The tool node to check
     * @return true if it's a function tool, false otherwise
     */
    private fun isToolNodeAFunction(toolNode: ObjectNode): Boolean =
        toolNode.has("type") &&
            toolNode.get("type").asText() == "function" &&
            toolNode.has("name")

    /**
     * Replaces a function tool with a Masaic managed tool if it's registered.
     *
     * @param toolsNode The array of tools
     * @param toolNode The current tool node
     * @param index The index of the current tool in the array
     */
    private fun replaceFunctionToolWithMasaicTool(
        toolsNode: ArrayNode,
        toolNode: ObjectNode,
        index: Int,
    ) {
        val functionName = toolNode.get("name").asText()
        // Use your toolService to check if this function should be modified
        val toolMetadata = toolService.getAvailableTool(functionName)
        if (toolMetadata != null) {
            // Create a new ObjectNode with only the "type" field set to the function name.
            // This satisfies your requirement to include only the type parameter.
            val newToolNode = mapper.createObjectNode()
            if (toolMetadata.protocol == ToolProtocol.MCP) {
                newToolNode.put("type", "mcp")
                newToolNode.put("name", toolMetadata.name)
            } else {
                newToolNode.put("type", functionName)
            }
            // Replace the current tool node with the new one.
            toolsNode.set(index, newToolNode)
        }
    }
}
