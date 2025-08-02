package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.model.CreateResponseRequest
import ai.masaic.openresponses.api.model.ResponseInputItemList
import ai.masaic.openresponses.api.service.MasaicResponseService
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import ai.masaic.openresponses.api.utils.PayloadFormatter
import ai.masaic.openresponses.api.validation.RequestValidator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.models.responses.ResponseCreateParams
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
class ResponseController(
    private val masaicResponseService: MasaicResponseService,
    private val payloadFormatter: PayloadFormatter,
    private val responseStore: ResponseStore,
    private val requestValidator: RequestValidator,
) {
    private val log = LoggerFactory.getLogger(ResponseController::class.java)
    val mapper = jacksonObjectMapper()

    @PostMapping("/responses", produces = [MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun createResponse(
        @RequestBody request: CreateResponseRequest,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
    ): ResponseEntity<*> {
        requestValidator.validateResponseRequest(request)
        payloadFormatter.formatResponseRequest(request)
        request.parseInput(mapper)
        val requestBodyJson = mapper.writeValueAsString(request)
        log.debug("Request body: $requestBodyJson")

        // If streaming is requested, set the appropriate content type and return a flow
        if (request.stream == true) {
            return ResponseEntity
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
            return ResponseEntity.ok(payloadFormatter.formatResponse(responseObj))
        }
    }

    @GetMapping("/responses/{responseId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getResponse(
        @PathVariable responseId: String,
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        try {
            return ResponseEntity.ok(payloadFormatter.formatResponse(masaicResponseService.getResponse(responseId)))
        } catch (e: ResponseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }

    @DeleteMapping("/responses/{responseId}")
    suspend fun deleteResponse(
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
    suspend fun listInputItems(
        @PathVariable responseId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "desc") order: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) before: String?,
    ): ResponseEntity<ResponseInputItemList> =
        try {
            ResponseEntity.ok(masaicResponseService.listInputItems(responseId, limit, order, after, before))
        } catch (e: ResponseNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
}
