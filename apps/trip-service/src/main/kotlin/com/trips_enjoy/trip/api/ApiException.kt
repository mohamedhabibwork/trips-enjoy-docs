package com.trips_enjoy.trip.api

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
    const val TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    const val REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"
    const val STOP_NOT_FOUND = "STOP_NOT_FOUND"
    const val REWARD_NOT_FOUND = "REWARD_NOT_FOUND"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
}