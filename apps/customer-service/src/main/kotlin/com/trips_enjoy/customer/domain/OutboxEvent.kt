package com.trips_enjoy.customer.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Transactional outbox event. Written in the same transaction as the
 * state change that produced it; OutboxPublisher (in
 * `application/OutboxPublisher.kt`) forwards to Kafka and stamps
 * `published_at`.
 *
 * Mirrors the shape used by audit-service and configuration-service.
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
)
