package com.trips_enjoy.admin.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A break-glass co-signature record. Per SECURITY_ARCHITECTURE.md §14
 * every super-admin action that exceeds a normal scope requires a
 * second super-admin's co-signature. Mirrors `admin.break_glass` per
 * docs/services/admin-service/ERD.md §3.
 *
 * The record lives until `expires_at` (default 7 days). After that,
 * the super-admin must re-co-sign for new actions.
 */
@Entity
@Table(name = "break_glass", schema = "admin")
class BreakGlass(
    @Id val id: UUID,
    @Column(name = "action_log_id", nullable = false) val actionLogId: UUID,
    @Column(name = "cosigner_kc_sub", nullable = false) val cosignerKcSub: UUID,
    @Column(name = "cosigner_email") var cosignerEmail: String? = null,
    @Column(nullable = false) val reason: String,
    @Column(nullable = false) val signature: String,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "revoked_by") var revokedBy: UUID? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    init {
        require(reason.length >= 8) { "break_glass reason must be >= 8 chars" }
        require(expiresAt.isAfter(occurredAt)) { "expires_at must be after occurred_at" }
    }

    fun revoke(actorId: UUID, at: Instant) {
        require(revokedAt == null) { "break_glass already revoked" }
        revokedAt = at
        revokedBy = actorId
        rowVersion += 1
    }

    fun isActive(at: Instant = Instant.now()): Boolean =
        revokedAt == null && expiresAt.isAfter(at)
}