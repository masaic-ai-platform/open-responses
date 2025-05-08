package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.exception.*
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import ai.masaic.openresponses.api.service.ResponseProcessingException
import ai.masaic.openresponses.api.service.ResponseStreamingException
import ai.masaic.openresponses.api.service.ResponseTimeoutException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.openai.errors.BadRequestException
import com.openai.errors.NotFoundException
import com.openai.errors.OpenAIException
import com.openai.errors.PermissionDeniedException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.errors.UnexpectedStatusCodeException
import com.openai.errors.UnprocessableEntityException
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.context.request.WebRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Global exception handler for the OpenResponses API.
 * This centralizes error handling across all controllers.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    val objectMapper: ObjectMapper,
) {
    // Logs full stack trace for 5xx errors, single-line for others
    private fun logError(
        status: HttpStatus,
        ex: Throwable,
        message: String,
    ) {
        if (status.is5xxServerError) {
            logger.error(ex) { message }
        } else {
            logger.error { message }
        }
    }

    @ExceptionHandler(OpenAIException::class)
    fun handleOpenAIException(ex: OpenAIException): ResponseEntity<ErrorResponse> {
        if (ex is BadRequestException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Bad request",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Bad request",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else if (ex is PermissionDeniedException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Permission denied",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Permission denied",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else if (ex is NotFoundException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Not found",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Not found",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else if (ex is UnprocessableEntityException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unprocessable entity",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unprocessable entity",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else if (ex is RateLimitException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Rate limit exceeded",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Rate limit exceeded",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else if (ex is UnexpectedStatusCodeException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unexpected status code",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unexpected status code",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else if (ex is UnauthorizedException) {
            val status = HttpStatus.valueOf(ex.statusCode())
            logError(status, ex, "OpenAI API error: ${ex.message}")
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(status).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unauthorized",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unauthorized",
                        param = ex.body().toString(),
                        code = status.value().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(status).body(errorResponse)
            }
        } else {
            val status = HttpStatus.INTERNAL_SERVER_ERROR
            logError(status, ex, "OpenAI API error: ${ex.message}")
            val errorResponse =
                ErrorResponse(
                    type = "api_error",
                    message = ex.message ?: "An unexpected error occurred",
                    param = null,
                    code = status.value().toString(),
                    timestamp = System.currentTimeMillis(),
                )
            return ResponseEntity.status(status).body(errorResponse)
        }
    }

    @ExceptionHandler(ResponseNotFoundException::class)
    fun handleResponseNotFoundException(ex: ResponseNotFoundException): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.NOT_FOUND
        logError(status, ex, "Response not found: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "not_found",
                message = ex.message ?: "Response not found",
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(ResponseTimeoutException::class)
    fun handleResponseTimeoutException(ex: ResponseTimeoutException): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.REQUEST_TIMEOUT
        logError(status, ex, "Request timed out: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "timeout_error",
                message = ex.message ?: "Request timed out",
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(ResponseProcessingException::class)
    fun handleResponseProcessingException(ex: ResponseProcessingException): ResponseEntity<ErrorResponse> {
        if (ex.cause is OpenAIException) {
            return handleOpenAIException(ex.cause as OpenAIException)
        }

        val status = HttpStatus.INTERNAL_SERVER_ERROR
        logError(status, ex, "Error processing response: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "processing_error",
                message = ex.message ?: "Error processing response",
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(ResponseStreamingException::class)
    fun handleResponseStreamingException(ex: ResponseStreamingException): ResponseEntity<ErrorResponse> {
        if (ex.cause is OpenAIException) {
            return handleOpenAIException(ex.cause as OpenAIException)
        }

        val status = HttpStatus.INTERNAL_SERVER_ERROR
        logError(status, ex, "Streaming error: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "streaming_error",
                message = ex.message ?: "Error in streaming response",
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.BAD_REQUEST
        logError(status, ex, "Illegal argument: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "invalid_request",
                message = ex.message ?: "Invalid request",
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        if (ex is ServerWebInputException) {
            val status = HttpStatus.BAD_REQUEST
            logError(status, ex, "Invalid request input: ${ex.body.title}")

            val errorResponse =
                ErrorResponse(
                    type = "invalid_request",
                    message = ex.body.title ?: "Invalid request",
                    param = null,
                    code = status.value().toString(),
                    timestamp = System.currentTimeMillis(),
                )
            return ResponseEntity.status(status).body(errorResponse)
        }

        val status = HttpStatus.valueOf(ex.statusCode.value())
        logError(status, ex, "Response status error: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "api_error",
                message = ex.message,
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(MismatchedInputException::class)
    fun handleMismatchedInputException(ex: MismatchedInputException): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.BAD_REQUEST
        logError(status, ex, "Mismatched input: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "api_error",
                message = "Invalid request. Please check your input.",
                param = null,
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    /**
     * Handles ResourceNotFoundException and returns a 404 NOT_FOUND response.
     */
    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFoundException(
        ex: ResourceNotFoundException,
        request: WebRequest,
    ): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.NOT_FOUND
        logError(status, ex, "Resource not found: ${ex.message}")
        
        val errorResponse =
            ErrorResponse(
                type = "not_found",
                message = ex.message ?: "Resource not found",
                param = request.getDescription(false).substringAfter("uri="),
                code = status.value().toString(),
                timestamp = Instant.now().toEpochMilli(),
            )
        
        return ResponseEntity.status(status).body(errorResponse)
    }

    /**
     * Handles FileStorageException and returns a 500 INTERNAL_SERVER_ERROR response.
     */
    @ExceptionHandler(FileStorageException::class)
    fun handleFileStorageException(
        ex: FileStorageException,
        request: WebRequest,
    ): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        logError(status, ex, "File storage error: ${ex.message}")
        
        val errorResponse =
            ErrorResponse(
                type = "storage_error",
                message = ex.message ?: "File storage error",
                param = request.getDescription(false).substringAfter("uri="),
                code = status.value().toString(),
                timestamp = Instant.now().toEpochMilli(),
            )
        
        return ResponseEntity.status(status).body(errorResponse)
    }

    /**
     * Handles VectorStoreException and returns a 500 INTERNAL_SERVER_ERROR response.
     */
    @ExceptionHandler(VectorStoreException::class)
    fun handleVectorStoreException(
        ex: VectorStoreException,
        request: WebRequest,
    ): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        logError(status, ex, "Vector store error: ${ex.message}")
        
        val errorResponse =
            ErrorResponse(
                type = "vector_store_error",
                message = ex.message ?: "Vector store error",
                param = request.getDescription(false).substringAfter("uri="),
                code = status.value().toString(),
                timestamp = Instant.now().toEpochMilli(),
            )
        
        return ResponseEntity.status(status).body(errorResponse)
    }

    /**
     * Handles OpenResponsesException and returns an appropriate response.
     */
    @ExceptionHandler(OpenResponsesException::class)
    fun handleOpenResponsesException(
        ex: OpenResponsesException,
        request: WebRequest,
    ): ResponseEntity<ErrorResponse> {
        val status =
            when (ex) {
                is FileNotFoundException -> HttpStatus.NOT_FOUND
                is VectorStoreNotFoundException -> HttpStatus.NOT_FOUND
                is VectorStoreFileNotFoundException -> HttpStatus.NOT_FOUND
                is VectorIndexingException -> HttpStatus.INTERNAL_SERVER_ERROR
                is VectorSearchException -> HttpStatus.INTERNAL_SERVER_ERROR
                else -> HttpStatus.INTERNAL_SERVER_ERROR
            }
        logError(status, ex, "OpenResponses error: ${ex.message}")
        
        val errorResponse =
            ErrorResponse(
                type =
                    ex.javaClass.simpleName
                        .replace("Exception", "")
                        .lowercase() + "_error",
                message = ex.message ?: "An error occurred",
                param = request.getDescription(false).substringAfter("uri="),
                code = status.value().toString(),
                timestamp = Instant.now().toEpochMilli(),
            )
        
        return ResponseEntity.status(status).body(errorResponse)
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationErrors(
        ex: WebExchangeBindException,
    ): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.BAD_REQUEST
        logError(status, ex, "Validation exception: ${ex.message}")
        val first = ex.bindingResult.fieldErrors.firstOrNull()

        val fieldPath = first?.field ?: "request"
        val defaultMessage = first?.defaultMessage ?: "Validation failed"

        val error =
            ErrorResponse(
                type = "invalid_request",
                message = "$fieldPath $defaultMessage",
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )

        return ResponseEntity.status(status).body(error)
    }

    @ExceptionHandler(ServerWebInputException::class)
    fun handleJsonBindingErrors(
        ex: ServerWebInputException,
    ): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.BAD_REQUEST
        logError(status, ex, "Json binding errors: ${ex.message}")
        val missing =
            (ex.cause?.cause as? MismatchedInputException)
                ?.humanReadablePath()

        val error =
            ErrorResponse(
                type = "invalid_request",
                message =
                    missing
                        ?.let { "Missing required parameter: '$it'" }
                        ?: "Malformed JSON payload",
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )

        return ResponseEntity.status(status).body(error)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: WebRequest? = null,
    ): ResponseEntity<ErrorResponse> {
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        logError(status, ex, "Unhandled exception: ${ex.message}")

        val errorResponse =
            ErrorResponse(
                type = "api_error",
                message = ex.message ?: "An unexpected error occurred",
                param = request?.getDescription(false)?.substringAfter("uri="),
                code = status.value().toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(status).body(errorResponse)
    }

    /** Converts Jackson's path list to dot-plus-bracket notation, e.g.
     *  /testing_criteria/0/name  ->  testing_criteria[0].name
     */
    private fun JsonMappingException.humanReadablePath(): String =
        buildString {
            path.forEach { ref ->
                when {
                    ref.fieldName != null -> { // object property
                        if (isNotEmpty()) append('.') // add dot only between segments
                        append(ref.fieldName)
                    }
                    ref.index >= 0 -> // array element
                        append("[${ref.index}]")
                }
            }
        }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ErrorResponse(
    val type: String? = null,
    val message: String? = null,
    val param: String? = null,
    val code: String? = null,
    val timestamp: Long? = null,
) 
