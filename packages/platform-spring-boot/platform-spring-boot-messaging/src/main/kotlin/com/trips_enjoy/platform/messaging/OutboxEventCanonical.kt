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
 * Canonical transactional outbox row per ADR-0028.
 *
 * The 11-column canonical schema:
 *
 * | column          | type         | notes                                    |
 * |-----------------|--------------|------------------------------------------|
 * | id              | UUID PK      | UUIDv7 row PK                            |
 * | event_id        | UUID UNIQUE  | UUIDv7 consumer dedup key                |
 * | topic           | TEXT         | Kafka topic                              |
 * | partition_key   | TEXT         | Kafka partition key                      |
 * | payload         | JSONB        | Serialized event payload                 |
 * | headers         | JSONB '{}'   | Event envelope headers                   |
 * | created_at      | TIMESTAMPTZ  | Insert timestamp                         |
 * | published_at    | TIMESTAMPTZ  | NULL while pending                       |
 * | attempts        | INT          | Default 0                                |
 * | last_error      | TEXT         | NULL unless retry failed                 |
 * | next_attempt_at | TIMESTAMPTZ  | Default now(); used by FOR UPDATE SKIP   |
 * |                 |              | LOCKED poll loop                         |
 *
 * Plus a CHECK constraint that `partition_key IS NOT NULL` and a partial
 * index `WHERE published_at IS NULL` for the poll loop.
 *
 * Sourced from the 6 distinct shapes across Kotlin services — see
 * docs/architecture/adrs/0028-outbox-event-schema.md for the full audit.
 *
 * The table is per-service (e.g. `payment.outbox`, `audit.outbox`); the
 * schema name is the service's own. Services that previously used the
 * 14-col `OutboxEvent` (audit/ledger/notification/identity) migrate to
 * this canonical 11-col shape in Phase D.
 */
@Entity
@Table(name = "outbox")
open class OutboxEventCanonical(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID,

    @Column(name = "event_id", nullable = false, unique = true)
    var eventId: UUID,

    @Column(name = "topic", nullable = false)
    var topic: String,

    @Column(name = "partition_key", nullable = false)
    var partitionKey: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    var payload: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, columnDefinition = "jsonb")
    var headers: String = "{}",

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error")
    var lastError: String? = null,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),
) {
    /**
     * Convenience: marks the row as successfully published at [at].
     */
    open fun markPublished(at: Instant) {
        publishedAt = at
    }

    /**
     * Convenience: increments [attempts], records the [error] message,
     * and schedules the next attempt at [nextAttempt].
     */
    open fun markFailed(error: String, nextAttempt: Instant) {
        attempts += 1
        lastError = error.take(2000)
        nextAttemptAt = nextAttempt
    }
}