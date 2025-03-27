package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.model.CreateResponseRequest
import ai.masaic.openresponses.api.model.ResponseInputItemList
import ai.masaic.openresponses.api.service.MasaicResponseService
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import ai.masaic.openresponses.api.utils.CoroutineMDCContext
import ai.masaic.openresponses.api.utils.PayloadFormatter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
@Tag(name = "Responses", description = "OpenAI Response API")
class ResponseController(
    private val masaicResponseService: MasaicResponseService,
    private val payloadFormatter: PayloadFormatter,
    private val responseStore: ResponseStore,
) {
    private val log = LoggerFactory.getLogger(ResponseController::class.java)
    val mapper = jacksonObjectMapper()

    @PostMapping("/responses", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Creates a model response",
        description = "Creates a model response. Provide text or image inputs to generate text or JSON outputs. Set stream=true to receive a streaming response.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = Response::class))],
            ),
        ],
    )
    suspend fun createResponse(
        @RequestBody request: CreateResponseRequest,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        // Extract trace ID from exchange
        val traceId = exchange.attributes["traceId"] as? String ?: headers["X-B3-TraceId"]?.firstOrNull() ?: "unknown"

        // Use our custom coroutine-aware MDC context
        return withContext(CoroutineMDCContext(mapOf("traceId" to traceId))) {
            payloadFormatter.formatResponseRequest(request)
            request.parseInput(mapper)
            val requestBodyJson = mapper.writeValueAsString(request)
            log.debug("Request body: $requestBodyJson")

            // If streaming is requested, set the appropriate content type and return a flow
            if (request.stream == true) {
                ResponseEntity
                    .ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(
                        masaicResponseService.createStreamingResponse(
                            mapper.readValue(
                                requestBodyJson,
                                ResponseCreateParams.Body::class.java,
                            ),
                            headers,
                            queryParams,
                        ),
                    )
            } else {
                // For non-streaming, return a regular response
                val responseObj =
                    masaicResponseService.createResponse(
                        mapper.readValue(
                            requestBodyJson,
                            ResponseCreateParams.Body::class.java,
                        ),
                        headers,
                        queryParams,
                    )

                log.debug("Response Body: $responseObj")
                ResponseEntity.ok(payloadFormatter.formatResponse(responseObj))
            }
        }
    }

    @GetMapping("/responses/{responseId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Retrieves a model response",
        description = "Retrieves a model response with the given ID.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = Response::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Response not found",
            ),
        ],
    )
    suspend fun getResponse(
        @Parameter(description = "The ID of the response to retrieve", required = true)
        @PathVariable responseId: String,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        // Extract trace ID from exchange
        val traceId = exchange.attributes["traceId"] as? String ?: headers["X-B3-TraceId"]?.firstOrNull() ?: "unknown"

        // Use our custom coroutine-aware MDC context
        return withContext(CoroutineMDCContext(mapOf("traceId" to traceId))) {
            try {
                ResponseEntity.ok(payloadFormatter.formatResponse(masaicResponseService.getResponse(responseId)))
            } catch (e: ResponseNotFoundException) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
            }
        }
    }

    @DeleteMapping("/responses/{responseId}")
    @Operation(
        summary = "Deletes a model response",
        description = "Deletes a model response with the given ID.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
            ),
            ApiResponse(
                responseCode = "404",
                description = "Response not found",
            ),
        ],
    )
    fun deleteResponse(
        @Parameter(description = "The ID of the response to delete", required = true)
        @PathVariable responseId: String,
    ): ResponseEntity<Map<String, Any>> {
        val deleted = responseStore.deleteResponse(responseId)
        return ResponseEntity.ok(
            mapOf(
                "id" to responseId,
                "deleted" to deleted,
                "object" to "response",
            ),
        )
    }

    @GetMapping("/responses/{responseId}/input_items")
    @Operation(
        summary = "Returns a list of input items for a given response",
        description = "Returns a list of input items for a given response.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = ResponseInputItem::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Response not found",
            ),
        ],
    )
    fun listInputItems(
        @Parameter(description = "The ID of the response to retrieve input items for", required = true)
        @PathVariable responseId: String,
        @Parameter(
            description = "A limit on the number of objects to be returned. Limit can range between 1 and 100, and the default is 20.",
        )
        @RequestParam(defaultValue = "20") limit: Int,
        @Parameter(
            description = "Sort order by the created_at timestamp of the object. asc for ascending and desc for descending. Defaults to desc.",
        )
        @RequestParam(defaultValue = "desc") order: String,
        @Parameter(description = "An item ID to list items after, used in pagination.")
        @RequestParam(required = false) after: String?,
        @Parameter(description = "An item ID to list items before, used in pagination.")
        @RequestParam(required = false) before: String?,
    ): ResponseEntity<ResponseInputItemList> =
        try {
            ResponseEntity.ok(masaicResponseService.listInputItems(responseId, limit, order, after, before))
        } catch (e: ResponseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
}
