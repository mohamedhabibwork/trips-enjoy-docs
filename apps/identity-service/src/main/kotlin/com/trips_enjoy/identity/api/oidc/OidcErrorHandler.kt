package com.trips_enjoy.identity.api.oidc

import com.trips_enjoy.identity.api.ApiException
import com.trips_enjoy.identity.application.oidc.OidcUpstreamException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Locale

/**
 * Translates exceptions thrown by `OidcController` into RFC 6749 §5.2
 * `error`/`error_description` envelopes with HTTP 400/401/502.
 *
 * Different from `ApiExceptionHandler` (which emits RFC 7807 ProblemDetail for
 * the internal identity-service API).
 */
@RestControllerAdvice(basePackageClasses = [OidcController::class])
class OidcErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(OidcClientException::class)
    fun client(exception: OidcClientException): ResponseEntity<OidcErrorEnvelope> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(OidcErrorEnvelope(error = exception.error, errorDescription = exception.errorDescription))

    @ExceptionHandler(ApiException::class)
    fun api(exception: ApiException): ResponseEntity<OidcErrorEnvelope> {
        val (status, error) = when (exception.status) {
            HttpStatus.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED to "invalid_client"
            HttpStatus.FORBIDDEN -> HttpStatus.FORBIDDEN to "access_denied"
            HttpStatus.NOT_FOUND -> HttpStatus.NOT_FOUND to "invalid_request"
            HttpStatus.BAD_REQUEST -> HttpStatus.BAD_REQUEST to "invalid_request"
            else -> HttpStatus.BAD_REQUEST to "invalid_request"
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(OidcErrorEnvelope(error = error, errorDescription = exception.message))
    }

    @ExceptionHandler(OidcUpstreamException::class)
    fun upstream(exception: OidcUpstreamException): ResponseEntity<OidcErrorEnvelope> {
        log.warn("Upstream Keycloak failure: {} {}", exception.statusCode, exception.upstreamBody)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(OidcErrorEnvelope(error = "server_error", errorDescription = "identity-service upstream failure"))
    }

    @ExceptionHandler(Exception::class)
    fun unknown(exception: Exception, locale: Locale): ResponseEntity<OidcErrorEnvelope> {
        log.error("Unhandled exception in OIDC controller", exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(OidcErrorEnvelope(error = "server_error", errorDescription = "unexpected error"))
    }
}
