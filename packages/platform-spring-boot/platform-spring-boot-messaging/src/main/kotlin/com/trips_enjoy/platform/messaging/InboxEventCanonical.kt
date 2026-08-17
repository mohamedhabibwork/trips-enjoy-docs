package com.trips_enjoy.platform.messaging

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Canonical inbox event per the platform messaging contract.
 *
 * The inbox table is the consumer-side companion to the canonical
 * [OutboxEventCanonical] outbox. Each row represents a Kafka message
 * that has been received and is either pending or already processed.
 *
 * Columns:
 * - [messageId] is the producer-side UUIDv7 `event_id` and is the dedup
 *   primitive — the unique constraint on `(consumer_group, message_id)`
 *   makes the inbox at-least-once-safe.
 * - [consumerGroup] scopes the dedup per consumer (e.g. `payment-service`).
 * - [payload] is the JSON-serialized event body.
 * - [receivedAt] is the wall-clock time the listener observed the message.
 * - [processedAt] is null until the handler completes successfully.
 */
@Entity
@Table(name = "inbox")
open class InboxEventCanonical(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "message_id", nullable = false)
    var messageId: UUID,

    @Column(name = "consumer_group", nullable = false)
    var consumerGroup: String,

    @Column(name = "topic", nullable = false)
    var topic: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    var payload: String,

    @Column(name = "received_at", nullable = false, updatable = false)
    var receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,
) {
    open fun markProcessed(at: Instant) {
        processedAt = at
    }
}