package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Alias projection of `RuleBinding` rows whose `rule_kind = 'od_corridor'`.
 * Mirrors `pricing.geo_overrides` per docs/services/pricing-service/ERD.md §3.
 * The lookup is `(origin_zone_id, destination_zone_id, ride_type)`.
 * Maintained as a separate physical table so a targeted GIST / BRIN
 * index on `(origin_zone_id, destination_zone_id)` can answer the
 * lookup in O(log n) without scanning the full rule_bindings table.
 */
@Entity
@Table(name = "geo_overrides", schema = "pricing")
class GeoOverride(
    @Id val id: UUID,
    @Column(name = "origin_zone_id", nullable = false) val originZoneId: UUID,
    @Column(name = "destination_zone_id", nullable = false) val destinationZoneId: UUID,
    @Column(name = "ride_type", nullable = false) var rideType: String = "*",
    @Column(name = "multiplier_adjustment", nullable = false) var multiplierAdjustment: BigDecimal,
    @Column(nullable = false) var version: Int = 1,
    @Column(name = "effective_from") var effectiveFrom: Instant? = null,
    @Column(name = "effective_to") var effectiveTo: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
) {
    init {
        require(multiplierAdjustment.toDouble() >= 0.0) { "multiplier_adjustment must be >= 0" }
    }

    fun isActive(at: Instant = Instant.now()): Boolean {
        val from = effectiveFrom
        val to = effectiveTo
        val fromOk = from == null || !from.isAfter(at)
        val toOk = to == null || to.isAfter(at)
        return fromOk && toOk
    }
}