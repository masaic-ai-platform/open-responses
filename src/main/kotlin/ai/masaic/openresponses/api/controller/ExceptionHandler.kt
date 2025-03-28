package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.service.ResponseNotFoundException
import ai.masaic.openresponses.api.service.ResponseProcessingException
import ai.masaic.openresponses.api.service.ResponseStreamingException
import ai.masaic.openresponses.api.service.ResponseTimeoutException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
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
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler(
    val objectMapper: ObjectMapper,
) {
    @ExceptionHandler(OpenAIException::class)
    fun handleOpenAIException(ex: OpenAIException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "OpenAI API error: ${ex.message}" }

        if (ex is BadRequestException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Bad request",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Bad request",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is PermissionDeniedException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Permission denied",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Permission denied",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is NotFoundException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Not found",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Not found",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is UnprocessableEntityException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unprocessable entity",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unprocessable entity",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is RateLimitException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Rate limit exceeded",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Rate limit exceeded",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is UnexpectedStatusCodeException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unexpected status code",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unexpected status code",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is RateLimitException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Rate limit exceeded",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Rate limit exceeded",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else if (ex is UnauthorizedException) {
            try {
                val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
                val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(
                    errorResponse["error"] ?: ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unauthorized",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                val errorResponse =
                    ErrorResponse(
                        type = "api_error",
                        message = ex.message ?: "Unauthorized",
                        param = ex.body().toString(),
                        code = ex.statusCode().toString(),
                        timestamp = System.currentTimeMillis(),
                    )
                return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse)
            }
        } else {
            val errorResponse =
                ErrorResponse(
                    type = "api_error",
                    message = ex.message ?: "An unexpected error occurred",
                    param = null,
                    code = "internal_server_error",
                    timestamp = System.currentTimeMillis(),
                )
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
        }
    }

    @ExceptionHandler(ResponseNotFoundException::class)
    fun handleResponseNotFoundException(ex: ResponseNotFoundException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Response not found: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "not_found",
                message = ex.message ?: "Response not found",
                param = null,
                code = null,
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    @ExceptionHandler(ResponseTimeoutException::class)
    fun handleResponseTimeoutException(ex: ResponseTimeoutException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Request timed out: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "timeout_error",
                message = ex.message ?: "Request timed out",
                param = null,
                code = "408",
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(errorResponse)
    }

    @ExceptionHandler(ResponseProcessingException::class)
    fun handleResponseProcessingException(ex: ResponseProcessingException): ResponseEntity<ErrorResponse> {
        if (ex.cause is OpenAIException) {
            return handleOpenAIException(ex.cause as OpenAIException)
        }

        logger.error(ex) { "Error processing response: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "processing_error",
                message = ex.message ?: "Error processing response",
                param = null,
                code = "500",
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }

    @ExceptionHandler(ResponseStreamingException::class)
    fun handleResponseStreamingException(ex: ResponseStreamingException): ResponseEntity<ErrorResponse> {
        if (ex.cause is OpenAIException) {
            return handleOpenAIException(ex.cause as OpenAIException)
        }

        logger.error(ex) { "Streaming error: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "streaming_error",
                message = ex.message ?: "Error in streaming response",
                param = null,
                code = "500",
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Illegal argument: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "invalid_request",
                message = ex.message ?: "Invalid request",
                param = null,
                code = "400",
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        if (ex is ServerWebInputException) {
            logger.error(ex) { "Invalid request input: ${ex.body.title}" }

            val errorResponse =
                ErrorResponse(
                    type = "invalid_request",
                    message = ex.body.title ?: "Invalid request",
                    param = null,
                    code = "400",
                    timestamp = System.currentTimeMillis(),
                )
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
        }

        logger.error(ex) { "Response status error: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "api_error",
                message = ex.message,
                param = null,
                code = ex.statusCode.toString(),
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(ex.statusCode).body(errorResponse)
    }

    @ExceptionHandler(MismatchedInputException::class)
    fun handleGenericException(ex: MismatchedInputException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Unhandled exception: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "api_error",
                message = "Invalid request. Please check your input.",
                param = null,
                code = "bad_request",
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Unhandled exception: ${ex.message}" }

        val errorResponse =
            ErrorResponse(
                type = "api_error",
                message = ex.message ?: "An unexpected error occurred",
                param = null,
                code = "internal_server_error",
                timestamp = System.currentTimeMillis(),
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
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
