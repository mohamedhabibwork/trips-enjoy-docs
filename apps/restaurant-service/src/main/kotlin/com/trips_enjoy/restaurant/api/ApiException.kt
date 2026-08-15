package com.trips_enjoy.restaurant.api

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
    const val RESTAURANT_NOT_FOUND = "RESTAURANT_NOT_FOUND"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val SLUG_TAKEN = "SLUG_TAKEN"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
}