package com.trips_enjoy.platform.error

import io.opentelemetry.api.trace.Span
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException
import jakarta.validation.ConstraintViolationException

/**
 * Maps platform exceptions and Spring MVC exceptions to RFC 7807 problem
 * detail responses. Content-Type is `application/problem+json` per the spec.
 *
 * This is the canonical replacement for the per-service `ApiExceptionHandler`
 * that existed in identity / audit / ledger / notification / configuration.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        exception: BusinessException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        if (exception.httpStatus >= 500) {
            log.error("Business exception [${exception.code}] on ${request.requestURI}", exception)
        } else {
            log.warn("Business exception [${exception.code}] on ${request.requestURI}: ${exception.message}")
        }
        val trace = currentTraceContext()
        val body = ErrorResponse.from(
            exception = exception,
            instance = request.requestURI,
            traceId = trace.first,
            spanId = trace.second,
        )
        return ResponseEntity
            .status(exception.httpStatus)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors = exception.bindingResult.fieldErrors.map { fe ->
            FieldError(
                field = fe.field,
                message = fe.defaultMessage ?: "invalid",
                code = fe.code,
            )
        }
        val business = ValidationException(fieldErrors = fieldErrors)
        val trace = currentTraceContext()
        val body = ErrorResponse.from(
            exception = business,
            instance = request.requestURI,
            traceId = trace.first,
            spanId = trace.second,
        )
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors = exception.constraintViolations.map { cv ->
            FieldError(
                field = cv.propertyPath.toString(),
                message = cv.message,
                code = cv.constraintDescriptor.annotation.annotationClass.simpleName,
            )
        }
        val business = ValidationException(fieldErrors = fieldErrors)
        val trace = currentTraceContext()
        val body = ErrorResponse.from(
            exception = business,
            instance = request.requestURI,
            traceId = trace.first,
            spanId = trace.second,
        )
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(
        exception: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val business = BusinessException(
            code = ErrorCode.VALIDATION_FAILED,
            message = "Malformed request body",
        )
        val trace = currentTraceContext()
        val body = ErrorResponse.from(
            exception = business,
            instance = request.requestURI,
            traceId = trace.first,
            spanId = trace.second,
        )
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception on ${request.requestURI}", exception)
        val business = BusinessException(
            code = ErrorCode.INTERNAL_ERROR,
            message = "Internal server error",
            cause = exception,
        )
        val trace = currentTraceContext()
        val body = ErrorResponse.from(
            exception = business,
            instance = request.requestURI,
            traceId = trace.first,
            spanId = trace.second,
        )
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(body)
    }

    private fun currentTraceContext(): Pair<String?, String?> {
        val span = Span.current()
        val traceId = if (span.spanContext.isValid) span.spanContext.traceId else null
        val spanId = if (span.spanContext.isValid) span.spanContext.spanId else null
        return traceId to spanId
    }
}
