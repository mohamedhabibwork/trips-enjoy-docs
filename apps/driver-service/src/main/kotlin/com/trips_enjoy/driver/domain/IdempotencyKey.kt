package com.trips_enjoy.driver.domain

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
 * Mirrors `driver.idempotency_keys` per docs/services/driver-service/ERD.md §3.
 * The unique index on `(scope, idem_key)` is the canonical dedup primitive.
 * Replays return the cached response; mismatched request bodies fail
 * with 422 IDEMPOTENCY_MISMATCH (mapped in ApiExceptionHandler).
 */
@Entity
@Table(name = "idempotency_keys", schema = "driver")
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
        const val SCOPE_DRIVER_CREATE = "driver_create"
        const val SCOPE_DRIVER_UPDATE = "driver_update"
        const val SCOPE_DRIVER_APPROVE = "driver_approve"
        const val SCOPE_DRIVER_REJECT = "driver_reject"
        const val SCOPE_DRIVER_SUSPEND = "driver_suspend"
        const val SCOPE_DRIVER_REINSTATE = "driver_reinstate"
        const val SCOPE_DRIVER_DISABLE = "driver_disable"
        const val SCOPE_DRIVER_ERASE = "driver_erase"
        const val SCOPE_DOCUMENT_ADD = "document_add"
        const val SCOPE_DOCUMENT_DELETE = "document_delete"
        const val SCOPE_ELIGIBILITY_GRANT = "eligibility_grant"
        const val SCOPE_ELIGIBILITY_REVOKE = "eligibility_revoke"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_DRIVER_CREATE, SCOPE_DRIVER_UPDATE, SCOPE_DRIVER_APPROVE,
            SCOPE_DRIVER_REJECT, SCOPE_DRIVER_SUSPEND, SCOPE_DRIVER_REINSTATE,
            SCOPE_DRIVER_DISABLE, SCOPE_DRIVER_ERASE,
            SCOPE_DOCUMENT_ADD, SCOPE_DOCUMENT_DELETE,
            SCOPE_ELIGIBILITY_GRANT, SCOPE_ELIGIBILITY_REVOKE,
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