package com.trips_enjoy.trip.domain

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
 * A single GPS location ping on a trip. Mirrors
 * `trip.trip_location_point` per docs/services/trip-service/ERD.md §3.
 *
 * Composite PK on (id, recorded_at) — this is the partitioned table.
 * The composite-PK here is fine because the entity has a single
 * `JpaRepository<TripLocationPoint, TripLocationPointKey>` that the
 * compiler handles well (only the admin-service entity had the
 * generic-inference bug due to its complex entity relationships).
 */
@Entity
@Table(name = "trip_location_point", schema = "trip")
@IdClass(TripLocationPointKey::class)
class TripLocationPoint(
    @Id val id: UUID,
    @Column(name = "trip_id", nullable = false) val tripId: UUID,
    @Column(nullable = false) val latitude: BigDecimal,
    @Column(nullable = false) val longitude: BigDecimal,
    @Column(name = "accuracy_m") val accuracyM: BigDecimal? = null,
    @Column(name = "speed_kmh") val speedKmh: BigDecimal? = null,
    @Column(name = "heading_deg") val headingDeg: BigDecimal? = null,
    @Column(name = "recorded_at", nullable = false) val recordedAt: Instant = Instant.now(),
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
) {
    init {
        require(latitude.toDouble() in -90.0..90.0) { "latitude must be in [-90, 90]" }
        require(longitude.toDouble() in -180.0..180.0) { "longitude must be in [-180, 180]" }
    }
}

data class TripLocationPointKey(
    val id: UUID = UUID(0L, 0L),
    val recordedAt: Instant = Instant.EPOCH,
) : Serializable