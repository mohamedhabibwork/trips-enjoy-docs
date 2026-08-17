package com.trips_enjoy.trip.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
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
 *
 * Phase C (platform DRY): extends [BaseEntity] so `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` are
 * inherited from the platform canonical shape. The corresponding
 * column migration is V6 (`created_by` / `updated_by` `UUID` ->
 * `VARCHAR(255)`, `row_version` -> `version`).
 */
@Entity
@Table(name = "trip_stop", schema = "trip")
class TripStop(
    @Column(name = "trip_id", nullable = false) val tripId: UUID,
    @Column(nullable = false) val sequence: Int,
    @Column(name = "zone_id") val zoneId: UUID? = null,
    @Column var address: String? = null,
    @Column(name = "arrived_at") var arrivedAt: Instant? = null,
    @Column(name = "departed_at") var departedAt: Instant? = null,
) : BaseEntity() {
    init {
        require(sequence >= 0) { "sequence must be >= 0" }
    }

    fun arrive(at: Instant) {
        arrivedAt = at
        updatedAt = at
        version += 1
    }

    fun depart(at: Instant) {
        require(arrivedAt != null) { "cannot depart before arriving" }
        require(!at.isBefore(arrivedAt)) { "departed_at must be >= arrived_at" }
        departedAt = at
        updatedAt = at
        version += 1
    }
}