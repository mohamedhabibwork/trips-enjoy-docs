package com.trips_enjoy.search.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A relevance tuning knob (per-vertical field boost). Mirrors
 * `search.relevance_config` per docs/services/search-service/ERD.md §3.
 *
 * Single-UUID PK + unique index on (tenant_id, vertical, field).
 *
 * Phase C (platform DRY): extends [BaseEntity] so the `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` columns
 * are inherited from the platform canonical shape. The corresponding
 * column migration is V6 (`created_by` / `updated_by` `UUID` →
 * `VARCHAR(255)`, `row_version` → `version`, plus `deleted_at`).
 */
@Entity
@Table(name = "relevance_config", schema = "search")
class RelevanceConfig(
    @Column(name = "tenant_id", nullable = false) var tenantId: String = "global",
    @Column(nullable = false) val vertical: String,
    @Column(nullable = false) val field: String,
    @Column(nullable = false) var boost: Double = 1.0,
    @Column(name = "decay_days") var decayDays: Int? = null,
    @Column(nullable = false) var enabled: Boolean = true,
    @Column(name = "updated_by_kc_sub", nullable = false) val updatedByKcSub: UUID,
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
) : BaseEntity() {
    init {
        require(field.length in 1..100) { "field length must be 1..100" }
        require(boost >= 0.0) { "boost must be >= 0.0" }
    }

    fun update(boost: Double?, decayDays: Int?, enabled: Boolean?, at: Instant) {
        boost?.let { this.boost = it }
        decayDays?.let { this.decayDays = it }
        enabled?.let { this.enabled = it }
        updatedAt = at
    }
}
