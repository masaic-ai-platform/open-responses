package dev.langchain4j.mcp.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import dev.langchain4j.agent.tool.ToolSpecification

class Converter {
    companion object {
        fun convert(tree: JsonNode): List<ToolSpecification> =
            ToolSpecificationHelper.toolSpecificationListFromMcpResponse(tree.path("result").path("tools") as ArrayNode)
                ?: emptyList()
    }
}
