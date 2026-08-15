package com.trips_enjoy.pricing.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.util.UUID

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException, req: WebRequest): ResponseEntity<ApiProblem> {
        val msg = e.message ?: "invalid state"
        val status = if (msg.contains("state ") || msg.contains("cannot ") || msg.contains("not active")) HttpStatus.CONFLICT
                     else HttpStatus.BAD_REQUEST
        return problem(status, if (status == HttpStatus.CONFLICT) ApiErrorCode.INVALID_STATE_TRANSITION else ApiErrorCode.VALIDATION_FAILED, msg, req)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException, req: WebRequest): ResponseEntity<ApiProblem> =
        problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, e.message ?: "", req)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException, req: WebRequest): ResponseEntity<ApiProblem> =
        problem(HttpStatus.NOT_FOUND, ApiErrorCode.QUOTE_NOT_FOUND, e.message ?: "", req)

    @ExceptionHandler(Exception::class)
    fun handleAny(e: Exception, req: WebRequest): ResponseEntity<ApiProblem> {
        log.error("unhandled exception", e)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "An unexpected error occurred. See the server logs for the correlation_id.", req,
        )
    }

    private fun problem(status: HttpStatus, code: String, detail: String, req: WebRequest): ResponseEntity<ApiProblem> {
        val correlationId = org.slf4j.MDC.get("request_id") ?: UUID.randomUUID().toString()
        val body = ApiProblem(
            type = "https://api.trips-enjoy.com/errors/$code",
            title = code,
            status = status.value(),
            detail = detail,
            instance = req.getDescription(false),
            code = code,
            correlationId = correlationId,
        )
        return ResponseEntity.status(status).body(body)
    }
}