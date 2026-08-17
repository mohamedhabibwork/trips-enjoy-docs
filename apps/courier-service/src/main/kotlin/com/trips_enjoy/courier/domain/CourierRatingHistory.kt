package com.trips_enjoy.courier.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One courier rating line item — appended when a food-order delivery
 * completes and the customer submits a rating. Mirrors
 * `courier.courier_rating_history` per docs/services/courier-service/ERD.md §3.
 * Append-only (V3 trigger).
 *
 * The Courier.aggregate's denormalised `rating` + `rating_count` are
 * maintained transactionally when a new line is added.
 */
@Entity
@Table(name = "courier_rating_history", schema = "courier")
class CourierRatingHistory(
    @Id val id: UUID,
    @Column(name = "courier_id", nullable = false) val courierId: UUID,
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