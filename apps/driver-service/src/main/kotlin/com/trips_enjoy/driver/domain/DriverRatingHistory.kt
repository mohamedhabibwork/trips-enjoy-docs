package com.trips_enjoy.driver.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One driver rating line item — appended when a trip completes and the
 * rider submits a rating. Mirrors `driver.driver_rating_history` per
 * docs/services/driver-service/ERD.md §3. Append-only (V3 trigger).
 *
 * The Driver.aggregate's denormalised `rating` + `rating_count` are
 * maintained transactionally when a new line is added.
 */
@Entity
@Table(name = "driver_rating_history", schema = "driver")
class DriverRatingHistory(
    @Id val id: UUID,
    @Column(name = "driver_id", nullable = false) val driverId: UUID,
    @Column(name = "request_id", nullable = false) val requestId: UUID,
    @Column(nullable = false) val service: String,
    @Column(nullable = false) var rating: Short,
    @Column var comment: String? = null,
    @Column(name = "rated_at", nullable = false) val ratedAt: Instant = Instant.now(),
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    init {
        require(rating.toInt() in 1..5) { "rating must be 1..5" }
    }
}