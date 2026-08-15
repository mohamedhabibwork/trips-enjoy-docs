package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * The payment intent aggregate — one row per payment intent.
 *
 * Mirrors `payment.payment_intents` per docs/services/payment-service/ERD.md §3.
 * Cross-service refs (customer_id, request_id, merchant_id, driver_id,
 * courier_id, wallet_id, city_id) are plain UUIDs WITHOUT database FKs
 * (DATA--003). The state machine is `created → authorized → captured /
 * voided`, optionally with `partially_refunded → refunded`. Field-level
 * immutability once `captured_at` is non-null (only `refunded_minor`
 * and `state` may mutate from then on).
 */
@Entity
@Table(name = "payment_intents", schema = "payment")
class PaymentIntent(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "request_id", nullable = false) val requestId: UUID,
    @Column(nullable = false) val service: String,
    @Column(name = "amount_minor", nullable = false) val amountMinor: Long,
    @Column(nullable = false, length = 3) val currency: String,
    @Column(name = "gateway_id", nullable = false) var gatewayId: String,
    @Column(name = "gateway_region", nullable = false) var gatewayRegion: String,
    @Column(name = "gateway_intent_id") var gatewayIntentId: String? = null,
    @Column(name = "gateway_token") var gatewayToken: String? = null,
    @Column(name = "capture_mode", nullable = false) var captureMode: String = "manual",
    @Column(nullable = false) var state: String = STATE_CREATED,
    @Column(name = "city_id") var cityId: UUID? = null,
    @Column var description: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var metadata: Map<String, Any?>? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "authorized_at") var authorizedAt: Instant? = null,
    @Column(name = "captured_at") var capturedAt: Instant? = null,
    @Column(name = "voided_at") var voidedAt: Instant? = null,
    @Column(name = "captured_minor") var capturedMinor: Long? = null,
    @Column(name = "refunded_minor", nullable = false) var refundedMinor: Long = 0L,
    @Column(name = "failure_code") var failureCode: String? = null,
    @Column(name = "failure_message") var failureMessage: String? = null,
    @Column(name = "wallet_id") var walletId: UUID? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
) {
    companion object {
        const val STATE_CREATED = "created"
        const val STATE_AUTHORIZED = "authorized"
        const val STATE_CAPTURED = "captured"
        const val STATE_VOIDED = "voided"
        const val STATE_FAILED = "failed"
        const val STATE_REFUNDED = "refunded"
        const val STATE_PARTIALLY_REFUNDED = "partially_refunded"

        val TERMINAL_STATES: Set<String> = setOf(STATE_VOIDED, STATE_FAILED, STATE_REFUNDED)
        val VALID_TRANSITIONS: Map<String, Set<String>> = mapOf(
            STATE_CREATED to setOf(STATE_AUTHORIZED, STATE_FAILED, STATE_VOIDED),
            STATE_AUTHORIZED to setOf(STATE_CAPTURED, STATE_VOIDED, STATE_FAILED),
            STATE_CAPTURED to setOf(STATE_REFUNDED, STATE_PARTIALLY_REFUNDED),
            STATE_PARTIALLY_REFUNDED to setOf(STATE_REFUNDED, STATE_PARTIALLY_REFUNDED),
        )
    }

    fun authorize(at: Instant, gatewayIntentId: String?) {
        check(state == STATE_CREATED) { "cannot authorize intent in state $state" }
        state = STATE_AUTHORIZED
        authorizedAt = at
        this.gatewayIntentId = gatewayIntentId
    }

    fun capture(at: Instant, capturedMinor: Long) {
        check(state == STATE_AUTHORIZED) { "cannot capture intent in state $state" }
        require(capturedMinor in 1..amountMinor) {
            "captured_minor=$capturedMinor not in 1..amountMinor=$amountMinor"
        }
        state = STATE_CAPTURED
        capturedAt = at
        this.capturedMinor = capturedMinor
    }

    fun voidAt(at: Instant, reason: String? = null) {
        check(state == STATE_CREATED || state == STATE_AUTHORIZED) {
            "cannot void intent in state $state"
        }
        state = STATE_VOIDED
        voidedAt = at
        failureCode = if (reason != null) "voided" else null
        failureMessage = reason
    }

    fun fail(code: String, message: String) {
        check(state in setOf(STATE_CREATED, STATE_AUTHORIZED)) {
            "cannot fail intent in state $state"
        }
        state = STATE_FAILED
        failureCode = code
        failureMessage = message
    }

    fun recordRefund(additionalRefundMinor: Long) {
        check(state == STATE_CAPTURED || state == STATE_PARTIALLY_REFUNDED) {
            "cannot refund intent in state $state"
        }
        require(refundedMinor + additionalRefundMinor <= (capturedMinor ?: amountMinor)) {
            "refund total would exceed captured"
        }
        refundedMinor += additionalRefundMinor
        state = if (refundedMinor >= (capturedMinor ?: amountMinor)) STATE_REFUNDED
                else STATE_PARTIALLY_REFUNDED
    }
}