package com.masaic.openai.api.controller

import com.masaic.openai.tool.ToolMetadata
import com.masaic.openai.tool.ToolService
import com.openai.models.responses.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
@Tag(name = "Playground", description = "Playground API")
class PlaygroundController(private val toolService: ToolService) {

    @GetMapping("/tools", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Retrieves available tools",
        description = "Retrieves a available tools.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = Response::class))]
            )
        ]
    )
    fun getTools(
    ): ResponseEntity<List<ToolMetadata>> {
        return toolService.listAvailableTools().let {
            ResponseEntity.ok(it)
        }
    }
}