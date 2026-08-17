package com.trips_enjoy.platform.error

/**
 * Platform-wide error codes (SCREAMING_SNAKE_CASE machine identifiers).
 * Sourced from `docs/shared/CONVENTIONS.md` §1.
 */
enum class ErrorCode(val httpStatus: Int) {
    VALIDATION_FAILED(400),
    UNAUTHENTICATED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    IDEMPOTENCY_KEY_REUSED(422),
    RATE_LIMITED(429),
    BUSINESS_RULE_VIOLATION(422),
    STATE_INVALID(409),
    INTERNAL_ERROR(500),
    DEPENDENCY_UNAVAILABLE(503),
    DEPENDENCY_TIMEOUT(504),
    BAD_GATEWAY(502),
    CIRCUIT_OPEN(503),
    BULKHEAD_FULL(503),
}
