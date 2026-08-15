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
 * One payment attempt (auth / capture / void / refund / verify) against a gateway.
 * Mirrors `payment.payment_attempts` per docs/services/payment-service/ERD.md §3.
 * Append-only: UPDATE and DELETE are blocked by V4 triggers.
 */
@Entity
@Table(name = "payment_attempts", schema = "payment")
class PaymentAttempt(
    @Id val id: UUID,
    @Column(name = "payment_intent_id", nullable = false) val paymentIntentId: UUID,
    @Column(nullable = false) val operation: String,
    @Column(name = "gateway_id", nullable = false) val gatewayId: String,
    @Column(name = "gateway_attempt_id") val gatewayAttemptId: String? = null,
    @Column(nullable = false) var state: String = STATE_STARTED,
    @Column(name = "amount_minor") var amountMinor: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb") var requestPayload: Map<String, Any?>? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb") var responsePayload: Map<String, Any?>? = null,
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(name = "error_message") var errorMessage: String? = null,
    @Column(name = "started_at", nullable = false) val startedAt: Instant = Instant.now(),
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column(name = "latency_ms") var latencyMs: Int? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    companion object {
        const val OP_AUTHORIZE = "authorize"
        const val OP_CAPTURE = "capture"
        const val OP_VOID = "void"
        const val OP_REFUND = "refund"
        const val OP_VERIFY = "verify"

        const val STATE_STARTED = "started"
        const val STATE_SUCCEEDED = "succeeded"
        const val STATE_FAILED = "failed"
        const val STATE_TIMED_OUT = "timed_out"
    }

    fun markSucceeded(responsePayload: Map<String, Any?>, at: Instant) {
        state = STATE_SUCCEEDED
        this.responsePayload = responsePayload
        finishedAt = at
        latencyMs = (at.toEpochMilli() - startedAt.toEpochMilli()).toInt()
    }

    fun markFailed(errorCode: String, errorMessage: String, at: Instant) {
        state = STATE_FAILED
        this.errorCode = errorCode
        this.errorMessage = errorMessage
        finishedAt = at
        latencyMs = (at.toEpochMilli() - startedAt.toEpochMilli()).toInt()
    }

    fun markTimedOut(at: Instant) {
        state = STATE_TIMED_OUT
        finishedAt = at
        latencyMs = (at.toEpochMilli() - startedAt.toEpochMilli()).toInt()
    }
}