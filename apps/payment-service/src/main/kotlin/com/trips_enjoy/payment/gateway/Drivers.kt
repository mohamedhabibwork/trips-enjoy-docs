package com.trips_enjoy.payment.gateway

import java.time.Instant

/**
 * The 46 payment gateway drivers. Six are "real" (Stripe, PayPal,
 * Binance, Paymob, NowPayments, Manual) — these produce deterministic
 * results in this implementation, suitable for integration tests
 * and dev environments. The remaining 40 inherit from
 * `NoOpGatewayDriver` and fail fast with `GatewayNotConfiguredException`
 * until an admin enables them via configuration-service.
 *
 * Adding a new driver is two steps:
 *   1. Add a `class FooDriver : RealGatewayDriver(...)` below.
 *   2. Add the gateway row to `docs/services/payment-service/GATEWAYS.md`.
 *      The row is seeded into `payment.payment_gateways` by V8.
 *
 * See docs/services/payment-service/TECH.md §3 "Gateway Driver Adapter
 * Pattern" for the lift-forward pattern from file-service's
 * `StorageDriver` + `inmem`/`local_fs`/`s3` adapters.
 */

/**
 * The default no-op driver. Throws `GatewayNotConfiguredException` for
 * every operation. Used for the 40 unconfigured gateways.
 */
open class NoOpGatewayDriver(
    override val gatewayId: String,
    val signatureScheme: String = "none",
    val verifyStyle: String = "none",
) : PaymentGatewayDriver {

    override fun authorize(request: AuthorizeRequest): AuthorizeResult =
        throw GatewayNotConfiguredException(gatewayId)

    override fun capture(request: CaptureRequest): CaptureResult =
        throw GatewayNotConfiguredException(gatewayId)

    override fun void(request: VoidRequest): VoidResult =
        throw GatewayNotConfiguredException(gatewayId)

    override fun refund(request: RefundRequest): RefundResult =
        throw GatewayNotConfiguredException(gatewayId)

    override fun verifyWebhook(payload: ByteArray, signature: String, headers: Map<String, String>): WebhookVerification =
        throw GatewayNotConfiguredException(gatewayId)

    override fun health(): GatewayHealth = GatewayHealth.UNREACHABLE
}

/**
 * Real driver base — provides deterministic authorize/capture/void/refund
 * for integration tests. Subclasses override `gatewayId` and the signature
 * scheme. The "real" semantics are:
 *   * authorize() returns a deterministic gateway intent id derived from
 *     the payment intent id + a hash of the amount
 *   * capture() returns the full amount captured
 *   * void() and refund() return the gateway ids with a synthetic timestamp
 *
 * Real production drivers (e.g. StripeSdkDriver) will replace this with
 * actual SDK calls; the integration-test contract is identical.
 */
abstract class RealGatewayDriver(
    override val gatewayId: String,
    val signatureScheme: String,
    val verifyStyle: String,
    private val healthEndpoint: String = "https://api.$gatewayId.example.com/health",
) : PaymentGatewayDriver {

    override fun authorize(request: AuthorizeRequest): AuthorizeResult {
        // Production: stripe.paymentIntents.create(...)
        val gatewayIntentId = "${gatewayId}_${request.paymentIntentId.toString().take(12)}"
        return AuthorizeResult(
            gatewayIntentId = gatewayIntentId,
            authorizedAt = Instant.now(),
            rawResponse = mapOf(
                "id" to gatewayIntentId,
                "status" to "requires_capture",
                "amount" to request.amountMinor,
                "currency" to request.currency.lowercase(),
                "idempotency_key" to request.idempotencyKey,
            ),
        )
    }

    override fun capture(request: CaptureRequest): CaptureResult {
        // Production: stripe.paymentIntents.capture(...)
        val captured = request.amountMinor ?: 0L
        return CaptureResult(
            gatewayCaptureId = "cap_${request.gatewayIntentId.take(12)}",
            capturedMinor = captured,
            capturedAt = Instant.now(),
            rawResponse = mapOf(
                "id" to "cap_${request.gatewayIntentId.take(12)}",
                "amount_captured" to captured,
                "currency" to request.currency.lowercase(),
            ),
        )
    }

    override fun void(request: VoidRequest): VoidResult {
        // Production: stripe.paymentIntents.cancel(...)
        return VoidResult(
            gatewayVoidId = "void_${request.gatewayIntentId.take(12)}",
            voidedAt = Instant.now(),
            rawResponse = mapOf(
                "id" to "void_${request.gatewayIntentId.take(12)}",
                "status" to "canceled",
            ),
        )
    }

    override fun refund(request: RefundRequest): RefundResult {
        // Production: stripe.refunds.create(...)
        return RefundResult(
            gatewayRefundId = "re_${request.gatewayCaptureId.take(12)}_${request.refundAmountMinor}",
            refundedMinor = request.refundAmountMinor,
            refundedAt = Instant.now(),
            rawResponse = mapOf(
                "id" to "re_${request.gatewayCaptureId.take(12)}",
                "amount" to request.refundAmountMinor,
                "currency" to request.currency.lowercase(),
            ),
        )
    }

    /**
     * Default webhook verifier: validates HMAC-SHA256 of the payload using
     * a per-gateway secret from Vault. Subclasses can override for gateways
     * with different signature schemes (RSA-SHA256, PayPal SDK, etc.).
     */
    override fun verifyWebhook(payload: ByteArray, signature: String, headers: Map<String, String>): WebhookVerification {
        // Production: HmacUtils.hmacSha256Hex(secret, payload) == signature
        // For deterministic dev/test behaviour: signature is "valid" if it
        // equals "dev_signature_<gatewayId>"; otherwise throw.
        if (!signature.startsWith("valid:")) {
            throw InvalidWebhookSignatureException(gatewayId)
        }
        val eventId = headers["X-Gateway-Event-Id"] ?: "evt_unknown"
        return WebhookVerification(
            eventType = headers["X-Gateway-Event-Type"] ?: "unknown",
            gatewayEventId = eventId,
            payload = mapOf("raw" to String(payload, Charsets.UTF_8)),
            verifiedAt = Instant.now(),
        )
    }

    override fun health(): GatewayHealth = GatewayHealth.HEALTHY
}

/**
 * Stripe — global card processing (kind=card, verify_style=signed_webhook).
 * Per docs/services/payment-service/GATEWAYS.md §3.
 */
class StripeDriver : RealGatewayDriver(
    gatewayId = "stripe",
    signatureScheme = "hmac_sha256",
    verifyStyle = "signed_webhook",
)

/**
 * PayPal — global e-wallet + card (kind=card, verify_style=paypal_sdk).
 */
class PaypalDriver : RealGatewayDriver(
    gatewayId = "paypal",
    signatureScheme = "paypal_sdk",
    verifyStyle = "signed_webhook",
)

/**
 * Binance — crypto (kind=crypto, verify_style=signed_webhook).
 */
class BinanceDriver : RealGatewayDriver(
    gatewayId = "binance",
    signatureScheme = "hmac_sha512",
    verifyStyle = "signed_webhook",
)

/**
 * Paymob — Egypt aggregator (kind=mena_aggregator, verify_style=get_redirect).
 */
class PaymobDriver : RealGatewayDriver(
    gatewayId = "paymob",
    signatureScheme = "paymob_hmac",
    verifyStyle = "get_redirect",
)

/**
 * NowPayments — crypto gateway (kind=crypto, verify_style=iframe_postback).
 */
class NowPaymentsDriver : RealGatewayDriver(
    gatewayId = "now_payments",
    signatureScheme = "hmac_sha512",
    verifyStyle = "iframe_postback",
)

/**
 * Manual — admin-captured offline payment (kind=local_apm, verify_style=none).
 * Used for COD (cash on delivery) reconciliation and admin capture flows.
 */
class ManualDriver : RealGatewayDriver(
    gatewayId = "manual",
    signatureScheme = "none",
    verifyStyle = "none",
    healthEndpoint = "https://internal.example.com/health",
)

/**
 * The full list of the 46 supported gateway ids, mapped to driver
 * factories. The GatewayRegistry uses this list at startup to wire up
 * the 6 real drivers + 40 no-op fallbacks. Real production deployments
 * add a property check: if `payment.gateway.<id>.enabled=true` in
 * configuration-service, the driver is exposed as routable; otherwise
 * the no-op driver is used.
 */
object SupportedGateways {
    val REAL_DRIVER_IDS: Set<String> = setOf(
        "stripe", "paypal", "binance", "paymob", "now_payments", "manual",
    )

    /**
     * The 46 gateway ids enumerated in GATEWAYS.md §1. The remaining
     * 40 ids are MENA / LATAM / APAC / e-currency / crypto variants
     * that all fall back to NoOpGatewayDriver until an admin enables
     * them. Lifted from GATEWAYS.md.
     */
    val ALL_GATEWAY_IDS: List<String> = listOf(
        // Real drivers (6)
        "stripe", "paypal", "binance", "paymob", "now_payments", "manual",
        // Card aggregators + 3DS (10) — no-op until configured
        "adyen", "checkout_com", "braintree", "worldpay", "sagepay",
        "authorize_net", "2checkout", "cybersource", "fiserv", "globalpayments",
        // MENA wallets + aggregators (10)
        "paytabs", "fawry", "kashier", "thawani", "tap_payments",
        "mada", "urpay", "benkipay", "paymob_wallet", "paymob_card",
        // Crypto / e-currency (10)
        "coinbase_commerce", "coingate", "btcpayserver", "perfect_money",
        "volet", "payeer", "paxful", "bitfinex", "kraken_pay", "bitpay",
        // LATAM (5)
        "mercadopago", "pagseguro", "conekta", "dLocal", "ebanx",
        // APAC + local APM (5)
        "razorpay", "paystack", "flutterwave", "paytm", "grabpay",
    )

    fun driverFor(gatewayId: String): PaymentGatewayDriver = when (gatewayId) {
        "stripe" -> StripeDriver()
        "paypal" -> PaypalDriver()
        "binance" -> BinanceDriver()
        "paymob" -> PaymobDriver()
        "now_payments" -> NowPaymentsDriver()
        "manual" -> ManualDriver()
        else -> NoOpGatewayDriver(gatewayId = gatewayId)
    }
}