package com.trips_enjoy.platform.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.net.URI

/**
 * RFC 7807 error envelope. The platform extension adds platform-specific
 * fields: `code` (machine-readable), `traceId`/`spanId`/`timestamp` (from
 * OTel), `errors[]` (field-level validation errors), and `downstream{}`
 * (when the error originated from another service).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val type: URI,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val traceId: String? = null,
    val spanId: String? = null,
    val timestamp: Instant = Instant.now(),
    val errors: List<FieldError>? = null,
    val downstream: DownstreamError? = null,
) {
    companion object {
        fun from(
            exception: BusinessException,
            instance: String,
            traceId: String? = null,
            spanId: String? = null,
        ): ErrorResponse = ErrorResponse(
            type = URI.create("https://platform.trips-enjoy.com/errors/${exception.code.name.lowercase().replace('_', '-')}"),
            title = exception.code.name.lowercase().replace('_', ' ')
                .replaceFirstChar { it.titlecase() },
            status = exception.httpStatus,
            detail = exception.message,
            instance = instance,
            code = exception.code.name,
            traceId = traceId,
            spanId = spanId,
            errors = (exception as? ValidationException)?.fieldErrors,
            downstream = exception.downstream,
        )
    }
}
