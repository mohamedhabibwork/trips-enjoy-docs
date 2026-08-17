package com.trips_enjoy.courier.api

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
    const val COURIER_NOT_FOUND = "COURIER_NOT_FOUND"
    const val DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND"
    const val ELIGIBILITY_NOT_FOUND = "ELIGIBILITY_NOT_FOUND"
    const val SHIFT_NOT_FOUND = "SHIFT_NOT_FOUND"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val ELIGIBILITY_ALREADY_ACTIVE = "ELIGIBILITY_ALREADY_ACTIVE"
    const val SHIFT_ALREADY_ACTIVE = "SHIFT_ALREADY_ACTIVE"
}