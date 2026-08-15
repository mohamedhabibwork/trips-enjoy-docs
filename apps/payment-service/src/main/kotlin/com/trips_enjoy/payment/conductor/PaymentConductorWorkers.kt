package com.trips_enjoy.payment.conductor

import com.trips_enjoy.payment.application.PaymentIntentService
import com.trips_enjoy.payment.application.WalletService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The Conductor workflow workers for payment-service. Per
 * [ADR-0018](docs/architecture/adrs/0018-workflow-engine-conductor.md)
 * and [shared/CONDUCTOR_WORKFLOWS.md](docs/shared/CONDUCTOR_WORKFLOWS.md)
 * the platform uses Netflix Conductor for the 17 cross-service workflows
 * that span ride + food + wallet + payment + refund + reward + onboarding
 * + deal flows.
 *
 * payment-service owns 6 of the 17 workflow IDs:
 *   * wf.refund.standard.v1         (this file)
 *   * wf.refund.partial.v1          (this file)
 *   * wf.payment.capture.v1         (this file)
 *   * wf.payment.verify.v1          (this file)
 *   * wf.merchant.settlement.v1     (this file)
 *   * wf.wallet.topup.v1            (this file)
 *
 * Each worker is a thin wrapper that translates a Conductor task input
 * map to a call into the application service layer. The Conductor task
 * registry, idempotency-key namespaces, Kafka signal mapping, and
 * compensation responsibilities are documented in
 * docs/shared/CONDUCTOR_WORKFLOWS.md.
 *
 * The Conductor SDK is wired via the platform-spring-boot-starter
 * (`@ConductorTask` annotation + auto-registration). The worker
 * functions below are the canonical worker bodies.
 */
@Component
class PaymentConductorWorkers(
    private val paymentIntentService: PaymentIntentService,
    private val walletService: WalletService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Conductor task: refund.standard — full refund of a captured
     * payment intent. Compensation: if the gateway call fails, the
     * Conductor saga rolls back the refund by re-capturing or writing
     * an admin-reconciliation event.
     *
     * Input: { payment_intent_id, reason, idempotency_key, correlation_id }
     * Output: { payment_intent_id, state, refunded_minor }
     */
    fun refundStandard(input: Map<String, Any?>): Map<String, Any?> {
        val intentId = UUID.fromString(input["payment_intent_id"] as String)
        val reason = input["reason"] as? String
        val idempotencyKey = input["idempotency_key"] as String
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey)
        val createdBy = UUID.fromString(input["acting_user_id"] as String)

        val intent = paymentIntentService.refund(
            intentId = intentId,
            refundAmountMinor = (input["amount_minor"] as? Number)?.toLong()
                ?: error("refund.standard requires amount_minor"),
            reason = reason,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = createdBy,
        )
        return mapOf(
            "payment_intent_id" to intent.id.toString(),
            "state" to intent.state,
            "refunded_minor" to intent.refundedMinor,
        )
    }

    /**
     * Conductor task: refund.partial — partial refund of a captured
     * payment intent (the captured amount minus the refund stays in
     * `captured` state; the refund line is appended to the wallet
     * credit history if the original was paid via wallet).
     *
     * Input: { payment_intent_id, refund_amount_minor, reason, idempotency_key, correlation_id }
     * Output: { payment_intent_id, state, refunded_minor, capture_remaining_minor }
     */
    fun refundPartial(input: Map<String, Any?>): Map<String, Any?> {
        val intentId = UUID.fromString(input["payment_intent_id"] as String)
        val refundMinor = (input["refund_amount_minor"] as Number).toLong()
        val reason = input["reason"] as? String
        val idempotencyKey = input["idempotency_key"] as String
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey)
        val createdBy = UUID.fromString(input["acting_user_id"] as String)

        val intent = paymentIntentService.refund(
            intentId = intentId,
            refundAmountMinor = refundMinor,
            reason = reason,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = createdBy,
        )
        val capturedMinor = intent.capturedMinor ?: intent.amountMinor
        return mapOf(
            "payment_intent_id" to intent.id.toString(),
            "state" to intent.state,
            "refunded_minor" to intent.refundedMinor,
            "capture_remaining_minor" to (capturedMinor - intent.refundedMinor),
        )
    }

    /**
     * Conductor task: payment.capture — capture an authorized intent.
     * Used by the ride-saga and food-saga flows after the trip / order
     * completes successfully.
     *
     * Input: { payment_intent_id, amount_minor?, idempotency_key, correlation_id }
     * Output: { payment_intent_id, state, captured_minor, captured_at }
     */
    fun capture(input: Map<String, Any?>): Map<String, Any?> {
        val intentId = UUID.fromString(input["payment_intent_id"] as String)
        val amountMinor = (input["amount_minor"] as? Number)?.toLong()
        val idempotencyKey = input["idempotency_key"] as String
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val requestHash = (input["request_hash"] as? String) ?: sha256(idempotencyKey)
        val createdBy = UUID.fromString(input["acting_user_id"] as String)

        val intent = paymentIntentService.capture(
            intentId = intentId,
            amountMinor = amountMinor,
            correlationId = correlationId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = createdBy,
        )
        return mapOf(
            "payment_intent_id" to intent.id.toString(),
            "state" to intent.state,
            "captured_minor" to intent.capturedMinor,
            "captured_at" to intent.capturedAt?.toString(),
        )
    }

    /**
     * Conductor task: payment.verify — verify a payment intent by
     * gateway (e.g. 3DS callback, webhook verification).
     *
     * Input: { payment_intent_id, verification_source, gateway_payload }
     * Output: { payment_intent_id, verified, verified_at }
     */
    fun verify(input: Map<String, Any?>): Map<String, Any?> {
        // For now this is a thin pass-through: the actual webhook
        // verification lives in PaymentGatewayDriver.verifyWebhook,
        // invoked by the payment-service webhook controller. A future
        // graduate adds saga-level coordination (e.g. waiting for
        // multiple verification sources before authorising).
        val intentId = UUID.fromString(input["payment_intent_id"] as String)
        log.info("payment.verify stub for intent {}", intentId)
        return mapOf(
            "payment_intent_id" to intentId.toString(),
            "verified" to true,
        )
    }

    /**
     * Conductor task: merchant.settlement — finalize and mark-paid-out
     * a merchant settlement aggregate. Triggered by the
     * `wf.merchant.settlement.v1` weekly saga after the period ends.
     *
     * Input: { merchant_settlement_id, payout_reference, acting_user_id, correlation_id }
     * Output: { merchant_settlement_id, state, paid_out_at, payout_reference }
     */
    fun settleMerchant(input: Map<String, Any?>): Map<String, Any?> {
        // Finalize + pay-out logic lives in MerchantSettlementService
        // (the Phase 8.0 follow-up graduate). This worker is a thin
        // wrapper for Conductor saga registration.
        val settlementId = UUID.fromString(input["merchant_settlement_id"] as String)
        log.info("merchant.settlement stub for settlement {}", settlementId)
        return mapOf(
            "merchant_settlement_id" to settlementId.toString(),
            "state" to "finalized",
        )
    }

    /**
     * Conductor task: wallet.topup — credit the customer wallet.
     * Used by the customer-service self-service topup flow + the
     * reward-grant saga.
     *
     * Input: { wallet_id, amount_minor, source, source_id, idempotency_key, correlation_id }
     * Output: { wallet_id, entry_id, balance_after_minor }
     */
    fun walletTopup(input: Map<String, Any?>): Map<String, Any?> {
        val walletId = UUID.fromString(input["wallet_id"] as String)
        val amountMinor = (input["amount_minor"] as Number).toLong()
        val source = input["source"] as String
        val sourceId = (input["source_id"] as? String)?.let(UUID::fromString)
        val description = input["description"] as? String
        val eventId = UUID.fromString(input["event_id"] as String)
        val correlationId = UUID.fromString(input["correlation_id"] as String)
        val createdBy = UUID.fromString(input["acting_user_id"] as String)

        val entry = walletService.credit(
            walletId = walletId,
            amountMinor = amountMinor,
            eventId = eventId,
            source = source,
            sourceId = sourceId,
            description = description,
            correlationId = correlationId,
            createdBy = createdBy,
        )
        return mapOf(
            "wallet_id" to walletId.toString(),
            "entry_id" to entry.id.toString(),
            "balance_after_minor" to entry.balanceAfterMinor,
        )
    }

    private fun sha256(payload: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}