package com.trips_enjoy.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An intermediate stop on a multi-stop trip. Mirrors `trip.trip_stop`
 * per docs/services/trip-service/ERD.md §3.
 *
 * `sequence` is 0-indexed (0 = pickup, 1 = first intermediate stop, ...,
 * N-1 = dropoff). The `arrived_at` + `departed_at` pair captures the
 * stop duration.
 */
@Entity
@Table(name = "trip_stop", schema = "trip")
class TripStop(
    @Id val id: UUID,
    @Column(name = "trip_id", nullable = false) val tripId: UUID,
    @Column(nullable = false) val sequence: Int,
    @Column(name = "zone_id") val zoneId: UUID? = null,
    @Column var address: String? = null,
    @Column(name = "arrived_at") var arrivedAt: Instant? = null,
    @Column(name = "departed_at") var departedAt: Instant? = null,
    @Column(name = "row_version", nullable = false) var rowVersion: Long = 1L,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
    @Column(name = "updated_by", nullable = false) var updatedBy: UUID = createdBy,
) {
    init {
        require(sequence >= 0) { "sequence must be >= 0" }
    }

    fun arrive(at: Instant) {
        arrivedAt = at
        updatedAt = at
        rowVersion += 1
    }

    fun depart(at: Instant) {
        require(arrivedAt != null) { "cannot depart before arriving" }
        require(!at.isBefore(arrivedAt)) { "departed_at must be >= arrived_at" }
        departedAt = at
        updatedAt = at
        rowVersion += 1
    }
}