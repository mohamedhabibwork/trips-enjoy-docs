package com.trips_enjoy.courier.domain

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
 * Mirrors `courier.idempotency_keys` per docs/services/courier-service/ERD.md §3.
 * The unique index on `(scope, idem_key)` is the canonical dedup primitive.
 */
@Entity
@Table(name = "idempotency_keys", schema = "courier")
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
        const val SCOPE_COURIER_CREATE = "courier_create"
        const val SCOPE_COURIER_UPDATE = "courier_update"
        const val SCOPE_COURIER_APPROVE = "courier_approve"
        const val SCOPE_COURIER_REJECT = "courier_reject"
        const val SCOPE_COURIER_SUSPEND = "courier_suspend"
        const val SCOPE_COURIER_REINSTATE = "courier_reinstate"
        const val SCOPE_COURIER_DISABLE = "courier_disable"
        const val SCOPE_COURIER_ERASE = "courier_erase"
        const val SCOPE_DOCUMENT_ADD = "document_add"
        const val SCOPE_DOCUMENT_DELETE = "document_delete"
        const val SCOPE_ELIGIBILITY_GRANT = "eligibility_grant"
        const val SCOPE_ELIGIBILITY_REVOKE = "eligibility_revoke"
        const val SCOPE_SHIFT_SCHEDULE = "shift_schedule"
        const val SCOPE_SHIFT_ACTIVATE = "shift_activate"
        const val SCOPE_SHIFT_COMPLETE = "shift_complete"
        const val SCOPE_SHIFT_CANCEL = "shift_cancel"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_COURIER_CREATE, SCOPE_COURIER_UPDATE, SCOPE_COURIER_APPROVE,
            SCOPE_COURIER_REJECT, SCOPE_COURIER_SUSPEND, SCOPE_COURIER_REINSTATE,
            SCOPE_COURIER_DISABLE, SCOPE_COURIER_ERASE,
            SCOPE_DOCUMENT_ADD, SCOPE_DOCUMENT_DELETE,
            SCOPE_ELIGIBILITY_GRANT, SCOPE_ELIGIBILITY_REVOKE,
            SCOPE_SHIFT_SCHEDULE, SCOPE_SHIFT_ACTIVATE,
            SCOPE_SHIFT_COMPLETE, SCOPE_SHIFT_CANCEL,
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