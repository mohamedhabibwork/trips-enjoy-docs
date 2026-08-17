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
 * Immutable history of every pricing geo-config write. Mirrors
 * `admin.pricing_geo_config_history` per docs/services/admin-service/ERD.md §3.
 * Append-only (V3 trigger).
 */
@Entity
@Table(name = "pricing_geo_config_history", schema = "admin")
class PricingGeoConfigHistory(
    @Id val id: UUID,
    @Column(name = "config_id", nullable = false) val configId: UUID,
    @Column(nullable = false) val version: Int,
    @Column(nullable = false) val action: String,
    @Column(name = "actor_kc_sub", nullable = false) val actorKcSub: UUID,
    @Column(name = "actor_email") var actorEmail: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: Map<String, Any?>,
    @Column var reason: String? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTION_CREATE = "create"
        const val ACTION_UPDATE = "update"
        const val ACTION_DISABLE = "disable"
        const val ACTION_ROLLBACK = "rollback"

        val VALID_ACTIONS: Set<String> = setOf(ACTION_CREATE, ACTION_UPDATE, ACTION_DISABLE, ACTION_ROLLBACK)
    }

    init {
        require(action in VALID_ACTIONS) { "unknown action $action" }
        require(version > 0) { "version must be > 0" }
    }
}