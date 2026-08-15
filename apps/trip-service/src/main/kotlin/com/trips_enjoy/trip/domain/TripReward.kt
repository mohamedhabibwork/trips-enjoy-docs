package com.trips_enjoy.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A reward grant for a completed trip. Mirrors `trip.trip_reward`
 * per docs/services/trip-service/ERD.md §3.
 *
 * A reward can be reversed via TripRewardReversal (the inverse
 * append-only table). When reversed, the original row's
 * `reversal_id` column points at the reversal row.
 */
@Entity
@Table(name = "trip_reward", schema = "trip")
class TripReward(
    @Id val id: UUID,
    @Column(name = "trip_id", nullable = false) val tripId: UUID,
    @Column(name = "driver_id", nullable = false) val driverId: UUID,
    @Column(name = "rider_id", nullable = false) val riderId: UUID,
    @Column(name = "amount_minor", nullable = false) val amountMinor: Long,
    @Column(nullable = false) var currency: String = "USD",
    @Column(name = "granted_at", nullable = false) val grantedAt: Instant = Instant.now(),
    @Column(nullable = false) var reason: String,
    @Column(name = "reversal_id") var reversalId: UUID? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
) {
    init {
        require(amountMinor > 0) { "amount_minor must be > 0" }
    }
}