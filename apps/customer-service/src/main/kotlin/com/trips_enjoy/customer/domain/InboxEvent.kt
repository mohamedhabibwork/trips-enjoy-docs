package com.trips_enjoy.customer.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Kafka dedup row — every consumed event has its `event_id` recorded
 * here so a redelivery is ignored. TTL 24h, purged by the
 * InboxMaintenanceJob.
 */
@Entity
@Table(name = "inbox", schema = "customer")
class InboxEvent(
    @Id @Column(name = "event_id") val eventId: UUID,
    @Column(nullable = false) val topic: String,
    @Column(name = "received_at", nullable = false) val receivedAt: Instant = Instant.now(),
    @Column(name = "processed_at") val processedAt: Instant? = null,
    @Column val error: String? = null,
)
