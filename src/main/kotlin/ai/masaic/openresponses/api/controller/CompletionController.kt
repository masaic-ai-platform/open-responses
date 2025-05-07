package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.client.CompletionStore
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.service.CompletionNotFoundException
import ai.masaic.openresponses.api.service.MasaicCompletionService
import ai.masaic.openresponses.api.utils.CoroutineMDCContext
import ai.masaic.openresponses.api.utils.PayloadFormatter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.models.chat.completions.ChatCompletion
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
@Tag(name = "Completions", description = "OpenAI Chat Completion API")
class CompletionController(
    private val masaicCompletionService: MasaicCompletionService,
    private val payloadFormatter: PayloadFormatter,
    private val completionStore: CompletionStore,
) {
    private val log = LoggerFactory.getLogger(CompletionController::class.java)
    val mapper = jacksonObjectMapper()

    @PostMapping("/chat/completions", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(
        summary = "Creates a chat completion",
        description = "Creates a chat completion. Provide messages to generate text outputs. Set stream=true to receive a streaming response.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = ChatCompletion::class))],
            ),
        ],
    )
    suspend fun createCompletion(
        @RequestBody request: CreateCompletionRequest,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        // Extract trace ID from exchange
        val traceId = exchange.attributes["traceId"] as? String ?: headers["X-B3-TraceId"]?.firstOrNull() ?: "unknown"

        payloadFormatter.formatCompletionRequest(request)
        // Use our custom coroutine-aware MDC context
        val requestBodyJson = mapper.writeValueAsString(request)
        log.debug("Request body: $requestBodyJson")

        // If streaming is requested, set the appropriate content type and return a flow
        if (request.stream == true) {
            return ResponseEntity
                .ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(
                    masaicCompletionService.createStreamingCompletion(
                        request,
                        headers,
                        queryParams,
                    ),
                )
        } else {
            // For non-streaming, return a regular response
            val completionObj =
                masaicCompletionService.createCompletion(
                    request,
                    headers,
                    queryParams,
                )

            log.debug("Response Body: {}", completionObj)
            return ResponseEntity.ok(completionObj)
        }
    }

    @GetMapping("/chat/completions/{completionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Retrieves a chat completion",
        description = "Retrieves a chat completion with the given ID.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
                content = [Content(schema = Schema(implementation = ChatCompletion::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Completion not found",
            ),
        ],
    )
    suspend fun getCompletion(
        @Parameter(description = "The ID of the chat completion to retrieve", required = true)
        @PathVariable completionId: String,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        // Extract trace ID from exchange
        val traceId = exchange.attributes["traceId"] as? String ?: headers["X-B3-TraceId"]?.firstOrNull() ?: "unknown"

        // Use our custom coroutine-aware MDC context
        return withContext(CoroutineMDCContext(mapOf("traceId" to traceId))) {
            try {
                ResponseEntity.ok(masaicCompletionService.getCompletion(completionId))
            } catch (e: CompletionNotFoundException) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
            }
        }
    }

    @DeleteMapping("/chat/completions/{completionId}")
    @Operation(
        summary = "Deletes a chat completion",
        description = "Deletes a chat completion with the given ID.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "OK",
            ),
            ApiResponse(
                responseCode = "404",
                description = "Completion not found",
            ),
        ],
    )
    suspend fun deleteCompletion(
        @Parameter(description = "The ID of the chat completion to delete", required = true)
        @PathVariable completionId: String,
    ): ResponseEntity<Map<String, Any>> {
        val deleted = completionStore.deleteCompletion(completionId)
        return ResponseEntity.ok(
            mapOf(
                "id" to completionId,
                "deleted" to deleted,
                "object" to "chat.completion",
            ),
        )
    }
} 
