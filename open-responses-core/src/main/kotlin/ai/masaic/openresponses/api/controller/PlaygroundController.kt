package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.tool.*
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
class PlaygroundController(
    private val toolService: ToolService,
    private val mcpToolExecutor: MCPToolExecutor,
) {
    @GetMapping("/tools", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTools(): ResponseEntity<List<ToolMetadata>> =
        toolService.listAvailableTools().let {
            ResponseEntity.ok(it)
        }

    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getModels(): ResponseEntity<AIModelsMetadata> {
        // List is not infinite Hard coding for now....
        val modelList =
            listOf(
                AIModelInfo(
                    id = "1",
                    name = "llama-3.2-3b-preview",
                    description = "",
                    provider = "groq",
                ),
                AIModelInfo(
                    id = "2",
                    name = "gpt-4o",
                    description = "",
                    provider = "openai",
                ),
                AIModelInfo(
                    id = "3",
                    name = "claude-3-5-sonnet-20241022",
                    description = "",
                    provider = "claude",
                ),
                AIModelInfo(
                    id = "4",
                    name = "qwen-2.5-32b",
                    description = "",
                    provider = "groq",
                ),
                AIModelInfo(
                    id = "5",
                    name = "claude-3-7-sonnet-20250219",
                    description = "",
                    provider = "claude",
                ),
            )
        return ResponseEntity.ok(AIModelsMetadata(models = modelList))
    }

    @PostMapping("/tools/mcp/execute", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun executeMCPTool(
        @RequestBody toolRequest: ExecuteToolRequest,
    ): String {
        val toolDefinition = toolService.findToolByName(toolRequest.name) ?: return "Tool ${toolRequest.name} not found."
        return mcpToolExecutor.executeTool(toolDefinition, jacksonObjectMapper().writeValueAsString(toolRequest.arguments)) ?: "no response from ${toolRequest.name}"
    }
}

data class ExecuteToolRequest(
    val name: String,
    val arguments: Map<String, Any>,
)
