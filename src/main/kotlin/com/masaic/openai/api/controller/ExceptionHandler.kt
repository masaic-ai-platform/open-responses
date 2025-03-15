package com.masaic.openai.api.controller

import com.masaic.openai.api.service.ResponseNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ResponseNotFoundException::class)
    fun handleResponseNotFoundException(ex: ResponseNotFoundException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            type = "not_found",
            message = ex.message ?: "Response not found",
            param = null,
            code = null
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            type = "api_error",
            message = ex.reason ?: "An error occurred",
            param = null,
            code = ex.statusCode.toString()
        )
        return ResponseEntity.status(ex.statusCode).body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            type = "api_error",
            message = ex.message ?: "An unexpected error occurred",
            param = null,
            code = "internal_server_error"
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
    }
}

data class ErrorResponse(
    val type: String,
    val message: String,
    val param: String?,
    val code: String?
) 