package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.client.CompletionStore
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.service.CompletionNotFoundException
import ai.masaic.openresponses.api.service.MasaicCompletionService
import ai.masaic.openresponses.api.utils.CoroutineMDCContext
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.api.validation.RequestValidator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
class CompletionController(
    private val masaicCompletionService: MasaicCompletionService,
    private val payloadFormatter: PayloadFormatter,
    private val completionStore: CompletionStore,
    private val requestValidator: RequestValidator,
) {
    private val log = LoggerFactory.getLogger(CompletionController::class.java)
    val mapper = jacksonObjectMapper()

    @PostMapping("/chat/completions", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun createCompletion(
        @RequestBody request: CreateCompletionRequest,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
    ): ResponseEntity<*> {
        requestValidator.validateCompletionRequest(request)
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
    suspend fun getCompletion(
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
    suspend fun deleteCompletion(
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
