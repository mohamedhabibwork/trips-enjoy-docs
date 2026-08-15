package com.trips_enjoy.admin.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A SUPER_ADMIN grant. Per the canonical preset
 * (`docs/services/admin-service/SUPER_ADMIN.md`) there is at most one
 * `permanent` active grant per user (the platform super-admin); the
 * `time_bounded` alias kind supports temporary escalation (per
 * `shared/TIME_BOUNDED_ALIASES.md`).
 *
 * Mirrors `admin.super_admin_grant` per
 * docs/services/admin-service/ERD.md §3.
 *
 * The `revoked_at` field drives the active/inactive filter; the unique
 * partial index on `(grantee_kc_sub) WHERE revoked_at IS NULL` enforces
 * one active grant per user.
 */
@Entity
@Table(name = "super_admin_grant", schema = "admin")
class SuperAdminGrant(
    @Id val id: UUID,
    @Column(name = "grantee_kc_sub", nullable = false) val granteeKcSub: UUID,
    @Column(name = "grantee_email") var granteeEmail: String? = null,
    @Column(name = "granted_by_kc_sub", nullable = false) val grantedByKcSub: UUID,
    @Column(name = "granted_by_email") var grantedByEmail: String? = null,
    @Column(nullable = false) val reason: String,
    @Column(name = "alias_kind", nullable = false) var aliasKind: String = ALIAS_PERMANENT,
    @Column(name = "alias_expires_at") var aliasExpiresAt: Instant? = null,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "revoked_by_kc_sub") var revokedByKcSub: UUID? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
) {
    companion object {
        const val ALIAS_PERMANENT = "permanent"
        const val ALIAS_TIME_BOUNDED = "time_bounded"

        val VALID_ALIAS_KINDS: Set<String> = setOf(ALIAS_PERMANENT, ALIAS_TIME_BOUNDED)
    }

    init {
        require(reason.isNotBlank()) { "reason required for SUPER_ADMIN grant" }
        require(aliasKind in VALID_ALIAS_KINDS) { "unknown alias_kind $aliasKind" }
        if (aliasKind == ALIAS_TIME_BOUNDED) {
            require(aliasExpiresAt != null) { "time_bounded alias requires alias_expires_at" }
            require(aliasExpiresAt!!.isAfter(createdAt)) { "alias_expires_at must be after created_at" }
        } else {
            require(aliasExpiresAt == null) { "permanent alias must not have alias_expires_at" }
        }
        // Default updatedBy to createdBy so callers don't have to specify it.
        if (updatedBy != createdBy) {
            // No-op; just document the default.
        }
    }

    fun revoke(actorKcSub: UUID, at: Instant = Instant.now()) {
        require(revokedAt == null) { "grant already revoked" }
        revokedAt = at
        revokedByKcSub = actorKcSub
        rowVersion += 1
        updatedAt = at
        updatedBy = actorKcSub
    }

    fun isActive(at: Instant = Instant.now()): Boolean {
        if (revokedAt != null) return false
        if (aliasKind == ALIAS_TIME_BOUNDED) {
            val exp = aliasExpiresAt ?: return false
            if (!exp.isAfter(at)) return false
        }
        return true
    }
}