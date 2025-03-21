package com.masaic.openai.api.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.masaic.openai.api.service.ResponseNotFoundException
import com.openai.errors.BadRequestException
import com.openai.errors.NotFoundException
import com.openai.errors.OpenAIException
import com.openai.errors.PermissionDeniedException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.errors.UnexpectedStatusCodeException
import com.openai.errors.UnprocessableEntityException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class GlobalExceptionHandler {

    val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

    @ExceptionHandler(OpenAIException::class)
    fun handleOpenAIException(ex: OpenAIException): ResponseEntity<ErrorResponse> {

        log.error("OpenAI error", ex)

        if(ex is BadRequestException){
            val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Bad request",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        } else if(ex is PermissionDeniedException) {
           val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Permission denied",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        }
        else if(ex is NotFoundException){
            val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Not found",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        }
        else if(ex is UnprocessableEntityException){
            val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Unprocessable entity",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        }
        else if(ex is RateLimitException){
            val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Rate limit exceeded",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        }
        else if(ex is UnexpectedStatusCodeException){
            val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Unexpected status code",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        }
        else if(ex is UnauthorizedException){
            val typeRef = object : TypeReference<Map<String, ErrorResponse>>() {}
            val errorResponse = objectMapper.readValue(ex.body().toString(), typeRef)
            return ResponseEntity.status(HttpStatus.valueOf(ex.statusCode())).body(errorResponse["error"]?:ErrorResponse(
                type = "api_error",
                message = ex.message ?: "Unauthorized",
                param = ex.body().toString(),
                code = ex.statusCode().toString()
            ))
        }
        else {
            val errorResponse = ErrorResponse(
                type = "api_error",
                message = ex.message ?: "An unexpected error occurred",
                param = null,
                code = "internal_server_error"
            )
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse)
        }
    }

    @ExceptionHandler(ResponseNotFoundException::class)
    fun handleResponseNotFoundException(ex: ResponseNotFoundException): ResponseEntity<ErrorResponse> {

        log.error("Response not found", ex)
        val errorResponse = ErrorResponse(
            type = "not_found",
            message = ex.message ?: "Response not found",
            param = null,
            code = null
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {

        log.error("Illegal argument", ex)
        val errorResponse = ErrorResponse(
            type = "invalid_request",
            message = ex.message ?: "Invalid request",
            param = null,
            code = "400"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {

        if(ex is ServerWebInputException){
            log.error("Request error", ex)
            val errorResponse = ErrorResponse(
                type = "invalid_request",
                message = ex.body.title ?: "Invalid request",
                param = null,
                code = "400"
            )
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse)
        }

        log.error("Response status error", ex)
        val errorResponse = ErrorResponse(
            type = "api_error",
            message = ex.message,
            param = null,
            code = ex.statusCode.toString()
        )
        return ResponseEntity.status(ex.statusCode).body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {

        log.error("Internal server error", ex)

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
    val type: String?=null,
    val message: String?=null,
    val param: String?=null,
    val code: String?=null
) 