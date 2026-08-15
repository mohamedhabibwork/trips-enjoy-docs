package com.trips_enjoy.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Transactional outbox event — one row per kafka publication. Mirrors
 * `payment.outbox_events` per docs/services/payment-service/ERD.md §3.
 * Written in the same DB transaction as the aggregate mutation that
 * produced it; the `OutboxPublisher` (200ms poll) reads and publishes.
 * Canonical pattern from customer-service / audit-service / ledger-service
 * / notification-service / configuration-service / reporting-service.
 */
@Entity
@Table(name = "outbox_events", schema = "payment")
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
) {
    fun markPublished(at: Instant) {
        publishedAt = at
    }

    fun markFailed(error: String, nextAttemptAt: Instant) {
        attempts += 1
        lastError = error
        this.nextAttemptAt = nextAttemptAt
    }
}