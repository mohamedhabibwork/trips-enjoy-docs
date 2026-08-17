package com.trips_enjoy.identity.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

class ApiException(val status: HttpStatus, val code: String, detail: String) : RuntimeException(detail)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(exception: ApiException, request: HttpServletRequest) = problem(exception.status, exception.code, exception.message ?: exception.code, request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(exception: MethodArgumentNotValidException, request: HttpServletRequest): ProblemDetail {
        val result = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request)
        result.setProperty("details", exception.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) })
        return result
    }

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
