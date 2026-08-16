package com.trips_enjoy.platform.error

/**
 * Validation exception carrying a list of field-level errors. Mapped to
 * RFC 7807 with `errors[]` array by [GlobalExceptionHandler].
 */
class ValidationException(
    val fieldErrors: List<FieldError>,
    message: String = "Validation failed",
) : BusinessException(ErrorCode.VALIDATION_FAILED, message)

data class FieldError(
    val field: String,
    val message: String,
    val code: String? = null,
)
