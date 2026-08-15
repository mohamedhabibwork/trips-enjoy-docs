package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Aggregated driver-rating-per-zone signal. Mirrors
 * `pricing.rating_density_cache` per docs/services/pricing-service/ERD.md §3.
 * Composite PK on `(zone_id, window_minutes)`. Refreshed on
 * `review.zone_aggregated.v1` from `trip-service` / `food-order-service`
 * / `search-service` (review projections).
 */
@Entity
@Table(name = "rating_density_cache", schema = "pricing")
@IdClass(RatingDensityCacheKey::class)
class RatingDensityCache(
    @Id @Column(name = "zone_id", nullable = false) val zoneId: UUID,
    @Id @Column(name = "window_minutes", nullable = false) val windowMinutes: Int,
    @Column(name = "avg_rating", nullable = false) var avgRating: BigDecimal,
    @Column(name = "sample_size", nullable = false) var sampleSize: Int,
    @Column(name = "computed_at", nullable = false) var computedAt: Instant = Instant.now(),
) {
    init {
        require(windowMinutes > 0) { "window_minutes must be > 0" }
        require(avgRating.toDouble() in 0.0..5.0) { "avg_rating must be 0..5" }
        require(sampleSize >= 0) { "sample_size must be >= 0" }
    }

    fun update(avgRating: BigDecimal, sampleSize: Int, at: Instant) {
        require(avgRating.toDouble() in 0.0..5.0) { "avg_rating must be 0..5" }
        require(sampleSize >= 0) { "sample_size must be >= 0" }
        this.avgRating = avgRating
        this.sampleSize = sampleSize
        computedAt = at
    }
}

data class RatingDensityCacheKey(
    val zoneId: UUID = UUID(0L, 0L),
    val windowMinutes: Int = 0,
) : Serializable