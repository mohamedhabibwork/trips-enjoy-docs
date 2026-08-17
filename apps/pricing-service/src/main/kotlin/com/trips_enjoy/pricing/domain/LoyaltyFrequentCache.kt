package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Aggregated frequent-zone loyalty signal. Mirrors
 * `pricing.loyalty_frequent_cache` per docs/services/pricing-service/ERD.md §3.
 * Composite PK on `(customer_id, zone_id)`. Refreshed on
 * `loyalty.frequent_zone.aggregated.v1` (debounced daily per
 * Phase 7 per TYPE_CATALOG.md §8.7).
 *
 * Tier mapping per the platform loyalty doctrine:
 *   silver    : 5-9 qualifying trips in the 30-day window
 *   gold      : 10-19 trips
 *   platinum  : 20+ trips
 */
@Entity
@Table(name = "loyalty_frequent_cache", schema = "pricing")
@IdClass(LoyaltyFrequentCacheKey::class)
class LoyaltyFrequentCache(
    @Id @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Id @Column(name = "zone_id", nullable = false) val zoneId: UUID,
    @Column(name = "trip_count_30d", nullable = false) var tripCount30d: Int,
    @Column(name = "tier_at_trip", nullable = false) var tierAtTrip: String,
    @Column(name = "most_recent_qualifying_at", nullable = false) var mostRecentQualifyingAt: Instant,
    @Column(name = "computed_at", nullable = false) var computedAt: Instant = Instant.now(),
    @Column(name = "expires_at", nullable = false) var expiresAt: Instant,
) {
    companion object {
        const val TIER_SILVER = "silver"
        const val TIER_GOLD = "gold"
        const val TIER_PLATINUM = "platinum"

        val VALID_TIERS: Set<String> = setOf(TIER_SILVER, TIER_GOLD, TIER_PLATINUM)
    }

    init {
        require(tripCount30d >= 0) { "trip_count_30d must be >= 0" }
        require(tierAtTrip in VALID_TIERS) { "unknown tier $tierAtTrip" }
        require(expiresAt.isAfter(computedAt)) { "expires_at must be after computed_at" }
    }

    fun update(tripCount: Int, tier: String, qualifyingAt: Instant, ttlUntil: Instant) {
        require(tripCount >= 0) { "trip_count must be >= 0" }
        require(tier in VALID_TIERS) { "unknown tier $tier" }
        require(ttlUntil.isAfter(qualifyingAt)) { "expires_at must be after qualifying_at" }
        this.tripCount30d = tripCount
        this.tierAtTrip = tier
        this.mostRecentQualifyingAt = qualifyingAt
        this.computedAt = qualifyingAt
        this.expiresAt = ttlUntil
    }

    fun isStale(at: Instant = Instant.now()): Boolean = !expiresAt.isAfter(at)
}

/**
 * IdClass for [LoyaltyFrequentCache]. Lives at file scope (not nested
 * inside the entity class) so that `@IdClass(LoyaltyFrequentCacheKey::class)`
 * can resolve it as a compile-time constant.
 */
data class LoyaltyFrequentCacheKey(
    val customerId: UUID = UUID(0L, 0L),
    val zoneId: UUID = UUID(0L, 0L),
) : Serializable