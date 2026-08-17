package com.trips_enjoy.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Pricing-service outbox row — Phase B of the platform-DRY initiative
 * (ADR-0028): the local entity persists into the canonical 11-column
 * outbox shape on top of the existing `pricing.outbox_events` table.
 *
 * The 11 canonical columns (id, event_id, topic, partition_key,
 * payload, headers, created_at, published_at, attempts, last_error,
 * next_attempt_at) are written directly via JPA. The service-local
 * columns (aggregate_type, aggregate_id, event_type, correlation_id,
 * created_by) live alongside them in the same table so the existing
 * constructor contract stays stable for callers.
 *
 * `event_id` and `partition_key` are auto-populated by `@PrePersist`.
 * The service-local fields are mirrored into the canonical `headers`
 * JSONB so downstream consumers see them without needing to know the
 * local schema.
 */
@Entity
@Table(name = "outbox_events", schema = "pricing")
class OutboxEvent(
    @Id val id: UUID,
    @Column(name = "aggregate_type", nullable = false) val aggregateType: String,
    @Column(name = "aggregate_id", nullable = false) val aggregateId: UUID,
    @Column(name = "event_type", nullable = false) val eventType: String,
    @Column(nullable = false) val topic: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: Map<String, Any?>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") var headers: Map<String, String>? = null,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(nullable = false) var attempts: Int = 0,
    @Column(name = "last_error") var lastError: String? = null,
    @Column(name = "next_attempt_at", nullable = false) var nextAttemptAt: Instant = Instant.now(),
    @Column(name = "published_at") var publishedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "created_by", nullable = false) val createdBy: UUID,

    // ----- Canonical columns (auto-populated by @PrePersist) -----------

    @Column(name = "event_id", nullable = false, unique = true)
    var eventId: UUID = UUID.randomUUID(),

    @Column(name = "partition_key", nullable = false)
    var partitionKey: String = aggregateId.toString(),
) {
    @PrePersist
    fun onPrePersist() {
        if (headers == null) headers = emptyMap()
        headers = headers!! + mapOf(
            "aggregate_type" to aggregateType,
            "event_type" to eventType,
            "correlation_id" to correlationId.toString(),
            "created_by" to createdBy.toString(),
        )
        if (partitionKey.isBlank()) partitionKey = aggregateId.toString()
    }

    init {
        if (headers == null) headers = emptyMap()
        headers = headers!! + mapOf(
            "aggregate_type" to aggregateType,
            "event_type" to eventType,
            "correlation_id" to correlationId.toString(),
            "created_by" to createdBy.toString(),
        )
        if (partitionKey.isBlank()) partitionKey = aggregateId.toString()
    }

    fun markPublished(at: Instant) {
        publishedAt = at
    }

    fun markFailed(error: String, nextAttemptAt: Instant) {
        attempts += 1
        lastError = error
        this.nextAttemptAt = nextAttemptAt
    }
}
