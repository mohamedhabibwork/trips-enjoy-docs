package com.trips_enjoy.payment.api

/**
 * The RFC 7807 (Problem Details for HTTP APIs) error envelope used by
 * every controller in payment-service. Per AGENTS.md and
 * docs/services/RECOMMENDATIONS.md every service must publish this
 * envelope on 4xx/5xx responses.
 *
 * Fields:
 *   * type            — a URI reference identifying the problem type
 *   * title           — a short human-readable title
 *   * status          — the HTTP status code
 *   * detail          — a human-readable explanation
 *   * instance        — the URI of the specific occurrence
 *   * code            — a machine-readable error code (see ApiErrorCode)
 *   * correlation_id   — the X-Request-Id header from the request
 *
 * The standard error codes are enumerated in
 * docs/services/payment-service/SRS.md §13.
 */
data class ApiProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val correlationId: String,
)

/**
 * The canonical error codes for payment-service. New codes must be
 * added to docs/services/payment-service/SRS.md §13 first.
 */
object ApiErrorCode {
    // Validation (400)
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val AMOUNT_MUST_BE_POSITIVE = "AMOUNT_MUST_BE_POSITIVE"
    const val CURRENCY_REQUIRED = "CURRENCY_REQUIRED"

    // AuthN/Z (401/403)
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"

    // Not Found (404)
    const val PAYMENT_INTENT_NOT_FOUND = "PAYMENT_INTENT_NOT_FOUND"
    const val WALLET_NOT_FOUND = "WALLET_NOT_FOUND"
    const val EARNINGS_NOT_FOUND = "EARNINGS_NOT_FOUND"

    // Conflict (409)
    const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"

    // Unprocessable (422)
    const val INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE"
    const val GATEWAY_NOT_ENABLED = "GATEWAY_NOT_ENABLED"
    const val GATEWAY_REGION_MISMATCH = "GATEWAY_REGION_MISMATCH"
    const val GATEWAY_AMOUNT_TOO_LARGE = "GATEWAY_AMOUNT_TOO_LARGE"
    const val GATEWAY_CURRENCY_UNSUPPORTED = "GATEWAY_CURRENCY_UNSUPPORTED"

    // Gateway upstream (502)
    const val GATEWAY_UNAVAILABLE = "GATEWAY_UNAVAILABLE"
    const val GATEWAY_TIMEOUT = "GATEWAY_TIMEOUT"
    const val CARD_DECLINED = "CARD_DECLINED"
    const val INVALID_GATEWAY_TOKEN = "INVALID_GATEWAY_TOKEN"

    // Webhook (400)
    const val INVALID_WEBHOOK_SIGNATURE = "INVALID_WEBHOOK_SIGNATURE"
}