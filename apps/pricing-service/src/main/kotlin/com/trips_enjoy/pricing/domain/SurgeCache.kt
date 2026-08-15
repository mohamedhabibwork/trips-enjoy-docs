package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Last-known surge multiplier per zone. Mirrors `pricing.surge_cache`
 * per docs/services/pricing-service/ERD.md §3. Refreshed on
 * `zone.surge.updated.v1` from `geolocation-service` (or admin override).
 * Used as a fallback when the in-memory cache is cold.
 *
 * Multiplier invariant: `multiplier >= 1.0` (1.0 = no surge).
 */
@Entity
@Table(name = "surge_cache", schema = "pricing")
class SurgeCache(
    @Id val zoneId: UUID,
    @Column(nullable = false) var multiplier: BigDecimal,
    @Column(nullable = false) var version: Int = 1,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
) {
    init {
        require(multiplier.toDouble() >= 1.0) { "multiplier must be >= 1.0" }
    }

    fun update(newMultiplier: BigDecimal, at: Instant) {
        require(newMultiplier.toDouble() >= 1.0) { "multiplier must be >= 1.0" }
        multiplier = newMultiplier
        version += 1
        updatedAt = at
    }
}