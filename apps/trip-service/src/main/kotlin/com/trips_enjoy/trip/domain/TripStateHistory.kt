package com.trips_enjoy.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One row per trip state transition. Mirrors `trip.trip_state_history`
 * per docs/services/trip-service/ERD.md §3.
 *
 * SINGLE-UUID PK (not composite) — the occurred_at column is a
 * regular indexed TIMESTAMPTZ. This avoids the Spring Data JPA +
 * Kotlin type-inference blocker that hit admin-service's `@IdClass`
 * design (see the uber-admin-service memory entry).
 *
 * Append-only (V3 trigger). This is the canonical lift-forward
 * pattern for time-series audit tables going forward.
 */
@Entity
@Table(name = "trip_state_history", schema = "trip")
class TripStateHistory(
    @Id val id: UUID,
    @Column(name = "trip_id", nullable = false) val tripId: UUID,
    @Column(name = "from_state") val fromState: String? = null,
    @Column(name = "to_state", nullable = false) val toState: String,
    @Column(name = "actor_kc_sub") val actorKcSub: UUID? = null,
    @Column(name = "actor_kind", nullable = false) val actorKind: String,
    @Column var reason: String? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val ACTOR_RIDER = "rider"
        const val ACTOR_DRIVER = "driver"
        const val ACTOR_ADMIN = "admin"
        const val ACTOR_SYSTEM = "system"
        const val ACTOR_DISPATCH = "dispatch"

        val VALID_ACTOR_KINDS: Set<String> = setOf(
            ACTOR_RIDER, ACTOR_DRIVER, ACTOR_ADMIN, ACTOR_SYSTEM, ACTOR_DISPATCH,
        )
    }

    init {
        require(actorKind in VALID_ACTOR_KINDS) { "unknown actor_kind $actorKind" }
    }
}