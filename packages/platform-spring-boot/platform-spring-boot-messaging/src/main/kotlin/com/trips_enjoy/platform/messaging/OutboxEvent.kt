package com.trips_enjoy.platform.messaging

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Canonical transactional outbox row. The platform invariant is that
 * every domain state change writes an [OutboxEvent] row in the same
 * database transaction; the [OutboxPublisher] (scheduled) reads
 * unpublished rows and forwards to Kafka.
 *
 * Sourced from the 4 identical copies in audit / ledger / notification /
 * identity-service. Schema lives in the consuming service's own database.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEvent(
    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    var id: UUID? = null,

    @Column(name = "aggregate_type", nullable = false, length = 64)
    var aggregateType: String? = null,

    @Column(name = "aggregate_id", length = 64)
    var aggregateId: String? = null,

    @Column(name = "topic", nullable = false, length = 128)
    var topic: String,

    @Column(name = "event_name", nullable = false, length = 128)
    var eventName: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    var payload: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", columnDefinition = "jsonb")
    var headers: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,
)
