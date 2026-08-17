package com.trips_enjoy.customer.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Append-only segment change log. INSERT-only at the DB level
 * (trigger installed in V3).
 */
@Entity
@Table(name = "customer_segment_history", schema = "customer")
class CustomerSegmentHistory(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "from_segment") val fromSegment: String? = null,
    @Column(name = "to_segment", nullable = false) val toSegment: String,
    @Column(name = "trigger", nullable = false) val trigger: String,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant = Instant.now(),
)
