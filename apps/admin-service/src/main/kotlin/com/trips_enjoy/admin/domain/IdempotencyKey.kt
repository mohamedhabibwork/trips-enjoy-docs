package com.trips_enjoy.admin.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Idempotency-Key middleware. The legacy scope+key composite pattern
 * (used by customer-service / driver-service / courier-service /
 * restaurant-service) is used here for backwards compatibility.
 * Mirrors `admin.idempotency_keys` per docs/services/admin-service/ERD.md §3.
 */
@Entity
@Table(name = "idempotency_keys", schema = "admin")
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
        const val SCOPE_ADMIN_ACTION = "admin_action"
        const val SCOPE_ADMIN_PRESET = "admin_preset"
        const val SCOPE_SUPER_ADMIN_GRANT = "super_admin_grant"
        const val SCOPE_SUPER_ADMIN_REVOKE = "super_admin_revoke"
        const val SCOPE_BREAK_GLASS_COSIGN = "break_glass_cosign"
        const val SCOPE_GEO_CONFIG_UPSERT = "geo_config_upsert"
        const val SCOPE_GEO_CONFIG_ROLLBACK = "geo_config_rollback"

        val VALID_SCOPES: Set<String> = setOf(
            SCOPE_ADMIN_ACTION, SCOPE_ADMIN_PRESET,
            SCOPE_SUPER_ADMIN_GRANT, SCOPE_SUPER_ADMIN_REVOKE,
            SCOPE_BREAK_GLASS_COSIGN,
            SCOPE_GEO_CONFIG_UPSERT, SCOPE_GEO_CONFIG_ROLLBACK,
        )
    }

    init {
        require(scope in VALID_SCOPES) { "unknown scope $scope" }
        require(idemKey.length in 8..200) { "idem_key length must be 8..200" }
        require(requestHash.length == 64) { "request_hash must be SHA-256 hex (64 chars)" }
    }

    fun isCompleted(): Boolean = completedAt != null

    fun recordResponse(status: Int, body: Map<String, Any?>, at: Instant) {
        check(!isCompleted()) { "idempotency response already recorded" }
        responseStatus = status
        responseBody = body
        completedAt = at
    }
}