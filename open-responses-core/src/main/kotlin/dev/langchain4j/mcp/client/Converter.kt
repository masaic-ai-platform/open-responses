package dev.langchain4j.mcp.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import mu.KotlinLogging

class Converter {
    companion object {
        private val log = KotlinLogging.logger {}

        fun convert(tree: JsonNode): List<ToolSpecification> {
            val array = tree.path("result").path("tools") as ArrayNode
            val result: MutableList<ToolSpecification> = ArrayList()
            for (tool in array) {
                try {
                    val builder = ToolSpecification.builder()
                    builder.name(tool["name"].asText())
                    if (tool.has("description")) {
                        builder.description(tool["description"].asText())
                    }
                    builder.parameters(ToolSpecificationHelper.jsonNodeToJsonSchemaElement(tool["inputSchema"]) as JsonObjectSchema)
                    result.add(builder.build())
                } catch (ex: Exception) {
                    log.warn { "Error occurred while parsing ${tool["name"].asText()}, error: ${ex.printStackTrace()}" }
                    log.warn { "Skipping this tool." }
                }
            }
            return result
        }
    }
}
