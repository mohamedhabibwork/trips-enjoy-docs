package com.trips_enjoy.foodorder.api

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
    const val ORDER_NOT_FOUND = "ORDER_NOT_FOUND"
    const val REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
}