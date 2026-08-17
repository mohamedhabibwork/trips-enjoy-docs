package com.trips_enjoy.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Reversal of a TripReward (e.g. dispute resolution). Mirrors
 * `trip.trip_reward_reversal` per docs/services/trip-service/ERD.md §3.
 * Append-only (V3 trigger).
 */
@Entity
@Table(name = "trip_reward_reversal", schema = "trip")
class TripRewardReversal(
    @Id val id: UUID,
    @Column(name = "reward_id", nullable = false) val rewardId: UUID,
    @Column(name = "trip_id", nullable = false) val tripId: UUID,
    @Column(name = "reversed_by_kc_sub", nullable = false) val reversedByKcSub: UUID,
    @Column(nullable = false) val reason: String,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "reversed_at", nullable = false) val reversedAt: Instant = Instant.now(),
)