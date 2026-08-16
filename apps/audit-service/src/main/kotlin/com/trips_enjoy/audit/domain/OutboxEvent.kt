package com.trips_enjoy.audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Transactional outbox for operational events (export.completed, consumer.lag,
 * hash_chain.verified, security.*, retention.*). Same shape as
 * identity-service's `OutboxEvent`.
 */
@Entity
@Table(name = "outbox", schema = "audit")
class OutboxEvent(
    @Id
    val id: UUID,

    @Column(name = "aggregate_type", nullable = false)
    val aggregateType: String,

    @Column(name = "aggregate_id")
    val aggregateId: UUID? = null,

    @Column(nullable = false)
    val topic: String,

    @Column(name = "event_name", nullable = false)
    val eventName: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val payload: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error")
    var lastError: String? = null,
)
