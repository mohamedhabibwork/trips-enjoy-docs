package com.trips_enjoy.pricing.api

data class ApiProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val correlationId: String,
)

object ApiErrorCode {
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val QUOTE_NOT_FOUND = "QUOTE_NOT_FOUND"
    const val QUOTE_NOT_ACTIVE = "QUOTE_NOT_ACTIVE"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val UNKNOWN_RULE_KIND = "UNKNOWN_RULE_KIND"
}