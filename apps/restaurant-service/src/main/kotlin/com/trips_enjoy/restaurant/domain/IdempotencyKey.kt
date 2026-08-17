package com.trips_enjoy.restaurant.domain

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
 * Mirrors `restaurant.idempotency_keys` per docs/services/restaurant-service/ERD.md §3.
 * The unique index on `(scope, idem_key)` is the canonical dedup primitive.
 */
@Entity
@Table(name = "idempotency_keys", schema = "restaurant")
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
        const val SCOPE_RESTAURANT_CREATE = "restaurant_create"
        const val SCOPE_RESTAURANT_UPDATE = "restaurant_update"
        const val SCOPE_RESTAURANT_SUBMIT = "restaurant_submit"
        const val SCOPE_RESTAURANT_APPROVE = "restaurant_approve"
        const val SCOPE_RESTAURANT_REJECT = "restaurant_reject"
        const val SCOPE_RESTAURANT_ONLINE = "restaurant_online"
        const val SCOPE_RESTAURANT_OFFLINE = "restaurant_offline"
        const val SCOPE_RESTAURANT_SUSPEND = "restaurant_suspend"
        const val SCOPE_RESTAURANT_REINSTATE = "restaurant_reinstate"
        const val SCOPE_RESTAURANT_CLOSE = "restaurant_close"
        const val SCOPE_RESTAURANT_RESUBMIT = "restaurant_resubmit"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_RESTAURANT_CREATE, SCOPE_RESTAURANT_UPDATE, SCOPE_RESTAURANT_SUBMIT,
            SCOPE_RESTAURANT_APPROVE, SCOPE_RESTAURANT_REJECT,
            SCOPE_RESTAURANT_ONLINE, SCOPE_RESTAURANT_OFFLINE,
            SCOPE_RESTAURANT_SUSPEND, SCOPE_RESTAURANT_REINSTATE,
            SCOPE_RESTAURANT_CLOSE, SCOPE_RESTAURANT_RESUBMIT,
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