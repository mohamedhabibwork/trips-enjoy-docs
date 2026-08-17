package com.trips_enjoy.configuration.api

import org.springframework.http.HttpStatus

/**
 * Domain exception with HTTP status + machine-readable code. The
 * ApiExceptionHandler turns this into a ProblemDetail (RFC 7807) response
 * with the platform's `code` / `correlationId` / `details[]` extensions.
 *
 * `details` is an optional list of {field, message} maps used for
 * validation failures (FR-004 / SRS §13).
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    detail: String,
    val details: List<Map<String, String>> = emptyList(),
) : RuntimeException(detail)
