package com.trips_enjoy.search.api

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
    const val JOB_NOT_FOUND = "JOB_NOT_FOUND"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
}