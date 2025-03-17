package com.masaic.openai.api.controller

import com.masaic.openai.api.service.MasaicResponseService
import com.masaic.openai.api.service.ResponseNotFoundException
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
import kotlin.jvm.optionals.getOrNull

@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
@Tag(name = "Responses", description = "OpenAI Response API")
class ResponseController(private val masaicResponseService: MasaicResponseService) {

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
        @RequestBody request: ResponseCreateParams.Body,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>
    ): ResponseEntity<*> {
        // If streaming is requested, set the appropriate content type and return a flow
        if (request._additionalProperties()["stream"]?.asBoolean()?.getOrNull() == true) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body(masaicResponseService.createStreamingResponse(request, headers, queryParams))
        }

        // For non-streaming, return a regular response
        val responseObj = masaicResponseService.createResponse(request, headers, queryParams)
        return ResponseEntity.ok(responseObj)
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
} 