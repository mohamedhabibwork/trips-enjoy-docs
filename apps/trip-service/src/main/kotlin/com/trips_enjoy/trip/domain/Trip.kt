package com.trips_enjoy.trip.domain

import com.trips_enjoy.platform.data.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The Trip aggregate — the active ride after dispatch. Mirrors
 * `trip.trip` per docs/services/trip-service/ERD.md §3.
 *
 * Lifecycle:
 *   pending → matched → driver_assigned → arrived → in_progress → completed
 *                                                                       ↓
 *                                                                    cancelled
 *                                                                       ↓
 *                                                                     no_show
 *
 * The `rating` is populated by TripRatingService after the trip
 * completes (separate flow). The `cancellation_reason` is required
 * when the trip enters `cancelled` or `no_show`.
 *
 * Phase C (platform DRY): extends [BaseEntity] so `id`, `createdAt`,
 * `updatedAt`, `createdBy`, `updatedBy`, `version`, and `deletedAt` are
 * inherited from the platform canonical shape. The corresponding
 * column migration is V6 (`created_by` / `updated_by` `UUID` ->
 * `VARCHAR(255)`, `row_version` -> `version`).
 */
@Entity
@Table(name = "trip", schema = "trip")
class Trip(
    @Column(name = "request_id", nullable = false) val requestId: UUID,
    @Column(name = "rider_id", nullable = false) val riderId: UUID,
    @Column(name = "driver_id") var driverId: UUID? = null,
    @Column(name = "vehicle_id") var vehicleId: UUID? = null,
    @Column(name = "city_id") var cityId: UUID? = null,
    @Column(name = "ride_type", nullable = false) var rideType: String,
    @Column(nullable = false) var status: String = STATUS_PENDING,
    @Column(name = "fare_id") var fareId: UUID? = null,
    @Column(name = "final_price_minor") var finalPriceMinor: Long? = null,
    @Column(name = "final_currency", nullable = false) var finalCurrency: String = "USD",
    @Column(name = "origin_zone_id") var originZoneId: UUID? = null,
    @Column(name = "destination_zone_id") var destinationZoneId: UUID? = null,
    @Column(name = "distance_km") var distanceKm: BigDecimal? = null,
    @Column(name = "duration_min") var durationMin: BigDecimal? = null,
    @Column(name = "correlation_id", nullable = false) var correlationId: UUID = UUID.randomUUID(),
    @Column(name = "matched_at") var matchedAt: Instant? = null,
    @Column(name = "arrived_at") var arrivedAt: Instant? = null,
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "completed_at") var completedAt: Instant? = null,
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null,
    @Column(name = "cancellation_reason") var cancellationReason: String? = null,
    @Column var rating: Short? = null,
    @Column(name = "rating_comment") var ratingComment: String? = null,
    @Column(name = "rating_at") var ratingAt: Instant? = null,
) : BaseEntity() {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_MATCHED = "matched"
        const val STATUS_DRIVER_ASSIGNED = "driver_assigned"
        const val STATUS_ARRIVED = "arrived"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_NO_SHOW = "no_show"

        val TERMINAL_STATUSES: Set<String> = setOf(STATUS_COMPLETED, STATUS_CANCELLED, STATUS_NO_SHOW)
    }

    init {
        require(status in setOf(STATUS_PENDING, STATUS_MATCHED, STATUS_DRIVER_ASSIGNED,
            STATUS_ARRIVED, STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_CANCELLED, STATUS_NO_SHOW)) {
            "unknown status $status"
        }
    }

    fun match(driverId: UUID, vehicleId: UUID, fareId: UUID, at: Instant) {
        check(status == STATUS_PENDING) { "cannot match trip in status $status" }
        this.driverId = driverId
        this.vehicleId = vehicleId
        this.fareId = fareId
        status = STATUS_MATCHED
        matchedAt = at
        updatedAt = at
        version += 1
    }

    fun arrive(at: Instant) {
        check(status == STATUS_MATCHED || status == STATUS_DRIVER_ASSIGNED) {
            "cannot arrive in status $status"
        }
        status = STATUS_ARRIVED
        arrivedAt = at
        updatedAt = at
        version += 1
    }

    fun start(distanceKm: BigDecimal, durationMin: BigDecimal, at: Instant) {
        check(status == STATUS_ARRIVED) { "cannot start in status $status" }
        this.distanceKm = distanceKm
        this.durationMin = durationMin
        status = STATUS_IN_PROGRESS
        startedAt = at
        updatedAt = at
        version += 1
    }

    fun complete(finalPriceMinor: Long, finalCurrency: String, at: Instant) {
        check(status == STATUS_IN_PROGRESS) { "cannot complete in status $status" }
        check(finalPriceMinor > 0) { "final_price_minor must be > 0" }
        this.finalPriceMinor = finalPriceMinor
        this.finalCurrency = finalCurrency
        status = STATUS_COMPLETED
        completedAt = at
        updatedAt = at
        version += 1
    }

    fun cancel(reason: String, at: Instant) {
        check(status !in TERMINAL_STATUSES) { "cannot cancel terminal trip" }
        status = STATUS_CANCELLED
        cancelledAt = at
        cancellationReason = reason
        updatedAt = at
        version += 1
    }

    fun noShow(reason: String, at: Instant) {
        check(status !in TERMINAL_STATUSES) { "cannot no-show terminal trip" }
        status = STATUS_NO_SHOW
        cancelledAt = at
        cancellationReason = reason
        updatedAt = at
        version += 1
    }

    fun rate(score: Short, comment: String?, at: Instant) {
        check(status == STATUS_COMPLETED) { "cannot rate non-completed trip" }
        require(score.toInt() in 1..5) { "rating must be 1..5" }
        rating = score
        ratingComment = comment
        ratingAt = at
        updatedAt = at
        version += 1
    }
}