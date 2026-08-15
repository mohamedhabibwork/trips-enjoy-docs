package com.trips_enjoy.payment.api

import com.trips_enjoy.payment.gateway.GatewayNotConfiguredException
import com.trips_enjoy.payment.gateway.GatewayOperationException
import com.trips_enjoy.payment.gateway.InvalidWebhookSignatureException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.util.UUID

/**
 * Translates exceptions to the RFC 7807 `ApiProblem` envelope. Per
 * AGENTS.md every 4xx/5xx response must use the canonical error
 * envelope; per docs/services/RECOMMENDATIONS.md §6.2a the canonical
 * error codes are listed in ApiErrorCode.
 *
 * The handler stamps the X-Request-Id from the request MDC into the
 * `correlationId` field so the error envelope is traceable from logs.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(GatewayOperationException::class)
    fun handleGatewayOperation(e: GatewayOperationException, req: WebRequest): ResponseEntity<ApiProblem> {
        val code = when (e.errorCode) {
            "CARD_DECLINED" -> ApiErrorCode.CARD_DECLINED
            "GATEWAY_TIMEOUT" -> ApiErrorCode.GATEWAY_TIMEOUT
            "GATEWAY_UNAVAILABLE" -> ApiErrorCode.GATEWAY_UNAVAILABLE
            "INVALID_GATEWAY_TOKEN" -> ApiErrorCode.INVALID_GATEWAY_TOKEN
            "AMOUNT_TOO_LARGE" -> ApiErrorCode.GATEWAY_AMOUNT_TOO_LARGE
            "CURRENCY_UNSUPPORTED" -> ApiErrorCode.GATEWAY_CURRENCY_UNSUPPORTED
            "REGION_MISMATCH" -> ApiErrorCode.GATEWAY_REGION_MISMATCH
            else -> ApiErrorCode.GATEWAY_UNAVAILABLE
        }
        val status = if (e.isTransient) HttpStatus.BAD_GATEWAY else HttpStatus.UNPROCESSABLE_ENTITY
        return problem(status, code, e.gatewayMessage, req)
    }

    @ExceptionHandler(GatewayNotConfiguredException::class)
    fun handleGatewayNotConfigured(e: GatewayNotConfiguredException, req: WebRequest): ResponseEntity<ApiProblem> =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.GATEWAY_NOT_ENABLED, e.message ?: "", req)

    @ExceptionHandler(InvalidWebhookSignatureException::class)
    fun handleInvalidSignature(e: InvalidWebhookSignatureException, req: WebRequest): ResponseEntity<ApiProblem> =
        problem(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_WEBHOOK_SIGNATURE, e.message ?: "", req)

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException, req: WebRequest): ResponseEntity<ApiProblem> {
        val msg = e.message ?: "invalid state"
        return when {
            msg.contains("balance") -> problem(
                HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.INSUFFICIENT_BALANCE, msg, req,
            )
            msg.contains("cannot ") || msg.contains("state ") -> problem(
                HttpStatus.CONFLICT, ApiErrorCode.INVALID_STATE_TRANSITION, msg, req,
            )
            else -> problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, msg, req)
        }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException, req: WebRequest): ResponseEntity<ApiProblem> =
        problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, e.message ?: "", req)

    @ExceptionHandler(java.util.NoSuchElementException::class)
    fun handleNotFound(e: java.util.NoSuchElementException, req: WebRequest): ResponseEntity<ApiProblem> =
        problem(HttpStatus.NOT_FOUND, ApiErrorCode.PAYMENT_INTENT_NOT_FOUND, e.message ?: "", req)

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