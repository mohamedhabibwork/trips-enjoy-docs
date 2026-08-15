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
 * The driver audit chain — every state-machine mutation writes one
 * row capturing the actor, the before/after snapshots, and a human-readable
 * reason. Mirrors `driver.driver_audit_log` per docs/services/driver-service/ERD.md §3.
 * Append-only (V3 trigger).
 */
@Entity
@Table(name = "driver_audit_log", schema = "driver")
class DriverAuditLog(
    @Id val id: UUID,
    @Column(name = "driver_id", nullable = false) val driverId: UUID,
    @Column(nullable = false) val action: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var before: Map<String, Any?>? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var after: Map<String, Any?>? = null,
    @Column(name = "actor_id", nullable = false) val actorId: UUID,
    @Column(name = "actor_email") var actorEmail: String? = null,
    @Column var reason: String? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTION_CREATED = "created"
        const val ACTION_APPROVED = "approved"
        const val ACTION_REJECTED = "rejected"
        const val ACTION_SUSPENDED = "suspended"
        const val ACTION_REINSTATED = "reinstated"
        const val ACTION_DISABLED = "disabled"
        const val ACTION_ERASED = "erased"
        const val ACTION_DOCUMENT_ADDED = "document_added"
        const val ACTION_DOCUMENT_VERIFIED = "document_verified"
        const val ACTION_DOCUMENT_REJECTED = "document_rejected"
        const val ACTION_DOCUMENT_EXPIRED = "document_expired"
        const val ACTION_CITY_GRANTED = "city_granted"
        const val ACTION_CITY_REVOKED = "city_revoked"
        const val ACTION_RATING_ADDED = "rating_added"
        const val ACTION_PRIMARY_VEHICLE_CHANGED = "primary_vehicle_changed"
        const val ACTION_PROFILE_UPDATED = "profile_updated"

        val VALID_ACTIONS: Set<String> = setOf(
            ACTION_CREATED, ACTION_APPROVED, ACTION_REJECTED, ACTION_SUSPENDED,
            ACTION_REINSTATED, ACTION_DISABLED, ACTION_ERASED,
            ACTION_DOCUMENT_ADDED, ACTION_DOCUMENT_VERIFIED,
            ACTION_DOCUMENT_REJECTED, ACTION_DOCUMENT_EXPIRED,
            ACTION_CITY_GRANTED, ACTION_CITY_REVOKED,
            ACTION_RATING_ADDED, ACTION_PRIMARY_VEHICLE_CHANGED,
            ACTION_PROFILE_UPDATED,
        )
    }

    init {
        require(action in VALID_ACTIONS) { "unknown audit action $action" }
    }
}