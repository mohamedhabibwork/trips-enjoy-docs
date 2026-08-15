package com.trips_enjoy.payment.gateway

import java.time.Instant
import java.util.UUID

/**
 * The port every payment gateway driver implements. The 46 supported
 * gateway drivers (see docs/services/payment-service/GATEWAYS.md) all
 * conform to this contract. Drivers are stateless and registered via
 * the `GatewayRegistry`; the registry resolves a driver by
 * `PaymentGateway.id`.
 *
 * The 6 "real" drivers in this implementation are: Stripe, PayPal,
 * Binance (crypto), Paymob (Egypt aggregator), NowPayments (crypto),
 * Manual (admin capture). The remaining 40 drivers are `NoOpGatewayDriver`
 * instances — they fail fast with `GatewayNotConfiguredException` so
 * the admin must enable them via configuration-service before they
 * become routable. This is the same lift-forward pattern as
 * file-service's `inmem` + `local_fs` drivers + 4 SDK stubs.
 */
interface PaymentGatewayDriver {
    /** The driver id; must match `PaymentGateway.id` in the registry. */
    val gatewayId: String

    /**
     * Authorize a payment intent against the gateway. Returns the
     * gateway-side intent id (e.g. `pi_…` for Stripe, `EC-…` for PayPal)
     * on success. On failure, throws `GatewayOperationException`.
     *
     * The driver MUST be idempotent on `(gateway_id, gateway_attempt_id)` —
     * if the same idem key is presented twice, the second call MUST
     * return the same result (or be deduplicated by the platform).
     */
    fun authorize(request: AuthorizeRequest): AuthorizeResult

    /**
     * Capture an authorized intent. For gateways that auto-capture
     * (capture_mode=auto), this is a no-op and the driver returns
     * `AutoCapturedResult`. For manual-capture gateways, this charges
     * the customer and returns the gateway capture id.
     */
    fun capture(request: CaptureRequest): CaptureResult

    /**
     * Void (cancel) an authorized intent that has not been captured.
     * After capture, refunds must be used instead.
     */
    fun void(request: VoidRequest): VoidResult

    /**
     * Refund a captured intent, either fully or partially. The
     * gateway-side refund id is returned for reconciliation.
     */
    fun refund(request: RefundRequest): RefundResult

    /**
     * Verify a webhook callback signature. The driver uses its
     * `signature_scheme` (HMAC-SHA256, RSA-SHA256, etc.) plus the
     * gateway-specific secret to validate the incoming payload.
     * Throws `InvalidWebhookSignatureException` on mismatch.
     */
    fun verifyWebhook(payload: ByteArray, signature: String, headers: Map<String, String>): WebhookVerification

    /**
     * Periodic health probe. The driver returns a synthetic health
     * verdict; the registry updates `PaymentGateway.health` and
     * `health_last_checked_at` accordingly. The probe is best-effort
     * and never throws — drivers should catch their own errors and
     * return `GatewayHealth.UNREACHABLE` instead.
     */
    fun health(): GatewayHealth
}

data class AuthorizeRequest(
    val paymentIntentId: UUID,
    val customerId: UUID,
    val amountMinor: Long,
    val currency: String,
    val gatewayToken: String,
    val gatewayRegion: String,
    val captureMode: String,
    val correlationId: UUID,
    val idempotencyKey: String,
    val metadata: Map<String, Any?>? = null,
)

data class AuthorizeResult(
    val gatewayIntentId: String,
    val authorizedAt: Instant,
    val rawResponse: Map<String, Any?>,
)

data class CaptureRequest(
    val paymentIntentId: UUID,
    val gatewayIntentId: String,
    val amountMinor: Long?,
    val currency: String,
    val correlationId: UUID,
    val idempotencyKey: String,
)

data class CaptureResult(
    val gatewayCaptureId: String,
    val capturedMinor: Long,
    val capturedAt: Instant,
    val rawResponse: Map<String, Any?>,
)

data class VoidRequest(
    val paymentIntentId: UUID,
    val gatewayIntentId: String,
    val correlationId: UUID,
    val idempotencyKey: String,
    val reason: String? = null,
)

data class VoidResult(
    val gatewayVoidId: String,
    val voidedAt: Instant,
    val rawResponse: Map<String, Any?>,
)

data class RefundRequest(
    val paymentIntentId: UUID,
    val gatewayIntentId: String,
    val gatewayCaptureId: String,
    val refundAmountMinor: Long,
    val currency: String,
    val reason: String? = null,
    val correlationId: UUID,
    val idempotencyKey: String,
)

data class RefundResult(
    val gatewayRefundId: String,
    val refundedMinor: Long,
    val refundedAt: Instant,
    val rawResponse: Map<String, Any?>,
)

data class WebhookVerification(
    val eventType: String,
    val gatewayEventId: String,
    val payload: Map<String, Any?>,
    val verifiedAt: Instant,
)

enum class GatewayHealth {
    HEALTHY, DEGRADED, UNREACHABLE
}

/**
 * Thrown when a driver has not been configured (no API key in Vault,
 * feature flag off, etc.). The admin must enable the gateway via
 * configuration-service before it becomes routable.
 */
class GatewayNotConfiguredException(gatewayId: String) :
    RuntimeException("gateway $gatewayId is not configured; enable via configuration-service.payment.gateway.$gatewayId")

/**
 * Thrown when a gateway returns an operationally-meaningful error.
 * The driver MUST map gateway-specific error codes to one of:
 *   * CARD_DECLINED         (customer-side: insufficient funds, lost card, ...)
 *   * GATEWAY_TIMEOUT        (transient: retry with exponential backoff)
 *   * GATEWAY_UNAVAILABLE    (transient: circuit-break and retry later)
 *   * INVALID_GATEWAY_TOKEN  (customer-side: token expired or invalid)
 *   * AMOUNT_TOO_LARGE       (gateway-side limit)
 *   * CURRENCY_UNSUPPORTED   (gateway cannot handle the requested currency)
 *   * REGION_MISMATCH        (gateway does not serve the requested region)
 */
class GatewayOperationException(
    val gatewayId: String,
    val errorCode: String,
    val gatewayMessage: String,
    val isTransient: Boolean,
) : RuntimeException("gateway $gatewayId failed: $errorCode ($gatewayMessage)")

class InvalidWebhookSignatureException(gatewayId: String) :
    RuntimeException("invalid webhook signature for gateway $gatewayId")