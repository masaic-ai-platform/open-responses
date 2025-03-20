package com.masaic.openai.api.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.masaic.openai.api.model.CreateResponseRequest
import com.masaic.openai.api.model.MasaicManagedTool
import com.masaic.openai.api.service.MasaicResponseService
import com.masaic.openai.api.service.ResponseNotFoundException
import com.masaic.openai.tool.ToolService
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
@Tag(name = "Responses", description = "OpenAI Response API")
class ResponseController(private val masaicResponseService: MasaicResponseService, private val toolService: ToolService) {

    val mapper = jacksonObjectMapper()

    @PostMapping("/responses", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Creates a model response",
        description = "Creates a model response. Provide text or image inputs to generate text or JSON outputs. Set stream=true to receive a streaming response.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = Response::class))]
            )
        ]
    )
    suspend fun createResponse(
        @RequestBody request: CreateResponseRequest,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>
    ): ResponseEntity<*> {
        updateMasaicManagedTools(request)
        request.parseInput(mapper)
        // If streaming is requested, set the appropriate content type and return a flow
        if (request.stream == true) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body(
                    masaicResponseService.createStreamingResponse(
                        mapper.readValue(
                            mapper.writeValueAsString(request),
                            ResponseCreateParams.Body::class.java
                        ), headers, queryParams
                    )
                )
        }

        // For non-streaming, return a regular response
        val responseObj = masaicResponseService.createResponse(
            mapper.readValue(
                mapper.writeValueAsString(request),
                ResponseCreateParams.Body::class.java
            ), headers, queryParams
        )

        return ResponseEntity.ok(updateMasaicManagedTools(responseObj))
    }

    @GetMapping("/responses/{responseId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Retrieves a model response",
        description = "Retrieves a model response with the given ID.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = Response::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Response not found"
            )
        ]
    )
    fun getResponse(
        @Parameter(description = "The ID of the response to retrieve", required = true)
        @PathVariable responseId: String,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>
    ): ResponseEntity<Response> {
        return try {
            ResponseEntity.ok(masaicResponseService.getResponse(responseId, headers, queryParams))
        } catch (e: ResponseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    @DeleteMapping("/responses/{responseId}")
    @Operation(
        summary = "Deletes a model response",
        description = "Deletes a model response with the given ID.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK"
            ),
            ApiResponse(
                responseCode = "404",
                description = "Response not found"
            )
        ]
    )
    fun deleteResponse(
        @Parameter(description = "The ID of the response to delete", required = true)
        @PathVariable responseId: String
    ): ResponseEntity<Void> {
        return try {
            //responseService.deleteResponse(responseId)
            ResponseEntity.ok().build()
        } catch (e: ResponseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    @GetMapping("/responses/{responseId}/input_items")
    @Operation(
        summary = "Returns a list of input items for a given response",
        description = "Returns a list of input items for a given response.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = ResponseInputItem::class))]
            ),
            ApiResponse(
                responseCode = "404",
                description = "Response not found"
            )
        ]
    )
    fun listInputItems(
        @Parameter(description = "The ID of the response to retrieve input items for", required = true)
        @PathVariable responseId: String,

        @Parameter(description = "A limit on the number of objects to be returned. Limit can range between 1 and 100, and the default is 20.")
        @RequestParam(defaultValue = "20") limit: Int,

        @Parameter(description = "Sort order by the created_at timestamp of the object. asc for ascending and desc for descending. Defaults to desc.")
        @RequestParam(defaultValue = "desc") order: String,

        @Parameter(description = "An item ID to list items after, used in pagination.")
        @RequestParam(required = false) after: String?,

        @Parameter(description = "An item ID to list items before, used in pagination.")
        @RequestParam(required = false) before: String?
    ): ResponseEntity<Any> {
        return try {
            val validLimit = limit.coerceIn(1, 100)
            val validOrder = if (order in listOf("asc", "desc")) order else "desc"

            val inputItems = masaicResponseService.listInputItems(responseId, validLimit, validOrder, after, before)
            ResponseEntity.ok(inputItems)
        } catch (e: ResponseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    private fun updateMasaicManagedTools(request: CreateResponseRequest) {
        request.tools = request.tools?.map { tool ->
            if (tool is MasaicManagedTool) {
                toolService.getFunctionTool(tool.type) ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Define tool ${tool.type} properly"
                )
            } else {
                tool
            }
        }?.toMutableList()
    }

    private fun updateMasaicManagedTools(response: Response): JsonNode {
        // Convert the Response object to a mutable JSON tree
        val rootNode = mapper.valueToTree<JsonNode>(response) as ObjectNode

        // Get the "tools" array node (if present)
        val toolsNode = rootNode.get("tools") as? ArrayNode
        if (toolsNode != null) {
            // Iterate over each tool node in the array
            for (i in 0 until toolsNode.size()) {
                val toolNode = toolsNode.get(i) as? ObjectNode ?: continue

                // Check if this tool is a function tool by looking at its "type" field.
                // Here we assume function tools have "type" == "function" and a "name" field.
                if (toolNode.has("type") && toolNode.get("type").asText() == "function" && toolNode.has("name")) {
                    val functionName = toolNode.get("name").asText()
                    // Use your toolService to check if this function should be modified
                    val toolMetadata = toolService.getAvailableTool(functionName)
                    if (toolMetadata != null) {
                        // Create a new ObjectNode with only the "type" field set to the function name.
                        // This satisfies your requirement to include only the type parameter.
                        val newToolNode = mapper.createObjectNode()
                        newToolNode.put("type", functionName)
                        // Replace the current tool node with the new one.
                        toolsNode.set(i, newToolNode)
                    }
                }
            }
        }
        return rootNode
    }
}