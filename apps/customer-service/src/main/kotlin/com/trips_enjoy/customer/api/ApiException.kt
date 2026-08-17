package com.trips_enjoy.customer.api

import org.springframework.http.HttpStatus

/**
 * Domain exception with HTTP status + machine-readable code. The
 * ApiExceptionHandler turns this into a ProblemDetail (RFC 7807)
 * response with the platform's `code` / `correlationId` / `details[]`
 * extensions.
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    detail: String,
    val details: List<Map<String, String>> = emptyList(),
) : RuntimeException(detail)
