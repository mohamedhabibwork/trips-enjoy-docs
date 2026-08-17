package com.trips_enjoy.audit.domain

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
 * Audit-service outbox row — Phase B of the platform-DRY initiative
 * (ADR-0028): the local entity persists into the canonical 11-column
 * `audit.outbox` table.
 *
 * The 11 canonical columns (id, event_id, topic, partition_key,
 * payload, headers, created_at, published_at, attempts, last_error,
 * next_attempt_at) are written directly via JPA. The audit-service
 * service-local columns (aggregate_type, aggregate_id, event_name,
 * correlation_id, created_by) live alongside them in the same table
 * so the existing constructor contract stays stable for callers
 * (ExportService, RetentionService).
 *
 * `event_id` (the consumer dedup key) and `partition_key` are
 * auto-populated by `@PrePersist`. The service-local fields are
 * mirrored into the canonical `headers` JSONB so downstream consumers
 * see them without needing to know the local schema.
 */
@Entity
@Table(name = "outbox", schema = "audit")
class OutboxEvent(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "aggregate_type", nullable = false)
    val aggregateType: String,

    @Column(name = "aggregate_id")
    val aggregateId: UUID? = null,

    @Column(name = "topic", nullable = false)
    val topic: String,

    @Column(name = "event_name", nullable = false)
    val eventName: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error")
    var lastError: String? = null,

    // ----- Canonical columns (auto-populated by @PrePersist) -----------

    @Column(name = "event_id", nullable = false, unique = true)
    var eventId: UUID = UUID.randomUUID(),

    @Column(name = "partition_key", nullable = false)
    var partitionKey: String = "audit",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, columnDefinition = "jsonb")
    var headers: String = "{}",

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),

    @Column(name = "correlation_id", nullable = false)
    var correlationId: UUID = UUID.randomUUID(),

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID(),
) {
    @PrePersist
    fun onPrePersist() {
        // Mirror the service-local fields into the canonical headers JSONB
        // so the row is self-describing for consumers without needing
        // extra columns.
        if (headers == "{}") headers = mapOf(
            "aggregate_type" to aggregateType,
            "event_name" to eventName,
        ).toString()
        if (partitionKey.isBlank()) partitionKey = aggregateId?.toString() ?: "audit"
    }

    init {
        // Also populate in init {} so unit tests (which don't run
        // @PrePersist) see the canonical headers and partition_key.
        if (headers == "{}") headers = mapOf(
            "aggregate_type" to aggregateType,
            "event_name" to eventName,
        ).toString()
        if (partitionKey.isBlank()) partitionKey = aggregateId?.toString() ?: "audit"
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
