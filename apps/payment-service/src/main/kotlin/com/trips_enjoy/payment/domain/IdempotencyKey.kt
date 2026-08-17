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
 * Idempotency-Key middleware state. One row per mutating REST call.
 * Mirrors `payment.idempotency_keys` per docs/services/payment-service/ERD.md §3.
 * The unique index on `(scope, idem_key)` is the canonical dedup primitive.
 *
 * The `request_hash` is a SHA-256 of the request body — if the same
 * `(scope, idem_key)` arrives with a different body, the call fails
 * with 422 IDEMPOTENCY_MISMATCH.
 */
@Entity
@Table(name = "idempotency_keys", schema = "payment")
class IdempotencyKey(
    @Id val id: UUID,
    @Column(nullable = false) val scope: String,
    @Column(name = "idem_key", nullable = false) val idemKey: String,
    @Column(name = "request_hash", nullable = false) val requestHash: String,
    @Column(name = "response_status") var responseStatus: Int? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb") var responseBody: Map<String, Any?>? = null,
    @Column(name = "locked_at", nullable = false) val lockedAt: Instant = Instant.now(),
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    companion object {
        const val SCOPE_PAYMENT_INTENT = "payment_intent"
        const val SCOPE_PAYMENT_CAPTURE = "payment_capture"
        const val SCOPE_PAYMENT_VOID = "payment_void"
        const val SCOPE_PAYMENT_REFUND = "payment_refund"
        const val SCOPE_WALLET_TOPUP = "wallet_topup"
        const val SCOPE_WALLET_DEBIT = "wallet_debit"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_PAYMENT_INTENT, SCOPE_PAYMENT_CAPTURE, SCOPE_PAYMENT_VOID,
            SCOPE_PAYMENT_REFUND, SCOPE_WALLET_TOPUP, SCOPE_WALLET_DEBIT
        )
    }

    init {
        require(scope in VALID_SCOPES) { "unknown scope $scope" }
        require(idemKey.length in 8..200) { "idem_key length must be 8..200" }
        require(requestHash.length == 64) { "request_hash must be a SHA-256 hex (64 chars)" }
    }

    fun isCompleted(): Boolean = completedAt != null

    fun recordResponse(status: Int, body: Map<String, Any?>, at: Instant) {
        check(!isCompleted()) { "idempotency response already recorded" }
        responseStatus = status
        responseBody = body
        completedAt = at
    }
}