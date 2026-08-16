package com.trips_enjoy.platform.messaging

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Canonical inbox event. The consumer side of the platform's outbox pattern:
 * services insert an [InboxEvent] before processing a Kafka message to
 * guarantee idempotency (the unique index on `event_id` + `topic` makes
 * the consumer at-least-once-safe; re-delivery hits the unique-violation).
 */
@Entity
@Table(name = "inbox_event")
class InboxEvent(
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID? = null,

    @Column(name = "event_id", nullable = false, length = 64)
    var eventId: String,

    @Column(name = "topic", nullable = false, length = 128)
    var topic: String,

    @Column(name = "consumer", length = 128)
    var consumer: String? = null,

    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "error", columnDefinition = "text")
    var error: String? = null,
)
