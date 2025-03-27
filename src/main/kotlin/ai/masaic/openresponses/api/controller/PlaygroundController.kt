package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.tool.AIModelInfo
import ai.masaic.openresponses.tool.AIModelsMetadata
import ai.masaic.openresponses.tool.ToolMetadata
import ai.masaic.openresponses.tool.ToolService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
@Tag(name = "Playground", description = "Playground API")
class PlaygroundController(
    private val toolService: ToolService,
) {
    @GetMapping("/tools", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Retrieves available tools",
        description = "Retrieves a available tools.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(type = "array", implementation = ToolMetadata::class))],
            ),
        ],
    )
    fun getTools(): ResponseEntity<List<ToolMetadata>> =
        toolService.listAvailableTools().let {
            ResponseEntity.ok(it)
        }

    @GetMapping("/models", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Retrieves available models",
        description = "Retrieves a available models for a model provider.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = AIModelsMetadata::class))],
            ),
        ],
    )
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
}
