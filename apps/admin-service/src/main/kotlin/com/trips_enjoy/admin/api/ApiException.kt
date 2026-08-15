package com.trips_enjoy.admin.api

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
    const val ACTION_NOT_FOUND = "ACTION_NOT_FOUND"
    const val SUPER_ADMIN_GRANT_NOT_FOUND = "SUPER_ADMIN_GRANT_NOT_FOUND"
    const val BREAK_GLASS_REQUIRED = "BREAK_GLASS_REQUIRED"
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val REQUIRES_SUPER_ADMIN = "REQUIRES_SUPER_ADMIN"
}