package com.trips_enjoy.customer.domain

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
 * Customer-service outbox row — Phase B of the platform-DRY initiative
 * (ADR-0028): the local entity persists into the canonical 11-column
 * outbox shape on top of the existing `customer.outbox` table.
 *
 * The 11 canonical columns (id, event_id, topic, partition_key,
 * payload, headers, created_at, published_at, attempts, last_error,
 * next_attempt_at) are written directly via JPA. The customer-service
 * local `claimed_at` column is preserved (the worker-claim contract
 * relies on it).
 *
 * `partition_key` is auto-populated by `@PrePersist`. The canonical
 * `headers` JSONB mirrors any service-local fields the caller provides.
 */
@Entity
@Table(name = "outbox", schema = "customer")
class OutboxEvent(
    @Id val id: UUID,
    @Column(nullable = false) val topic: String,
    @Column(name = "event_id", nullable = false) val eventId: UUID,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") val headers: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "claimed_at") var claimedAt: Instant? = null,
    @Column(name = "published_at") var publishedAt: Instant? = null,
    @Column(nullable = false) var attempts: Int = 0,
    @Column(name = "last_error") var lastError: String? = null,

    // ----- Canonical columns (auto-populated by @PrePersist) -----------

    @Column(name = "partition_key", nullable = false)
    var partitionKey: String = "customer",

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),
) {
    @PrePersist
    fun onPrePersist() {
        if (partitionKey.isBlank()) partitionKey = "customer"
    }

    init {
        if (partitionKey.isBlank()) partitionKey = "customer"
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
