package com.trips_enjoy.platform.error

/**
 * Platform-standard business exception. Carries [ErrorCode] + httpStatus, an
 * optional scoped [details] map (typically field-level validation errors),
 * and a flag marking whether the error originated from a downstream service
 * (in which case the [DownstreamError] block is rendered in the response).
 */
open class BusinessException(
    val code: ErrorCode,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val downstream: DownstreamError? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val httpStatus: Int
        get() = code.httpStatus
}

/**
 * Marker for errors that originated from a downstream service. Rendered
 * into the RFC 7807 `downstream{}` block by [GlobalExceptionHandler].
 */
data class DownstreamError(
    val service: String,
    val code: String,
    val status: Int,
    val traceId: String? = null,
    val latencyMs: Long? = null,
    val attempt: Int? = null,
)
