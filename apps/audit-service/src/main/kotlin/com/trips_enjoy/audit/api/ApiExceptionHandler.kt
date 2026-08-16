package com.trips_enjoy.audit.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.time.Instant

/** Thin wrapper over RuntimeException carrying the HTTP status + envelope code. */
class ApiException(val status: HttpStatus, val code: String, detail: String) : RuntimeException(detail)

/**
 * Platform error envelope per docs/shared/CONVENTIONS.md §1 (RFC 7807 +
 * `code` + `correlationId` + `timestamp`). See
 * docs/architecture/DOWNSTREAM_ERROR_CATALOG.md for the canonical codes.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(exception: ApiException, request: HttpServletRequest): ProblemDetail =
        problem(exception.status, exception.code, exception.message ?: exception.code, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(exception: MethodArgumentNotValidException, request: HttpServletRequest): ProblemDetail {
        val result = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request)
        result.setProperty(
            "details",
            exception.bindingResult.fieldErrors.map {
                mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid"))
            },
        )
        return result
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(exception: HttpMessageNotReadableException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body is missing or unreadable", request)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(exception: MethodArgumentTypeMismatchException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Parameter ${exception.name} has an invalid value", request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegal(exception: IllegalArgumentException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.message ?: "Bad request", request)

    private fun problem(status: HttpStatus, code: String, detail: String, request: HttpServletRequest): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).also {
            it.type = URI.create("urn:trips-enjoy:error:$code")
            it.title = status.reasonPhrase
            it.instance = URI.create(request.requestURI)
            it.setProperty("code", code)
            it.setProperty("correlationId", request.getAttribute("correlationId"))
            it.setProperty("timestamp", Instant.now().toString())
        }
}
