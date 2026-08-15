package com.trips_enjoy.courier.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Idempotent inbox event — one row per consumed kafka event. Mirrors
 * `courier.inbox_events` per docs/services/courier-service/ERD.md §3.
 * The unique index on `(source_topic, source_event_id)` is the dedup primitive.
 */
@Entity
@Table(name = "inbox_events", schema = "courier")
class InboxEvent(
    @Id val id: UUID,
    @Column(name = "source_topic", nullable = false) val sourceTopic: String,
    @Column(name = "source_event_id", nullable = false) val sourceEventId: UUID,
    @Column(name = "event_type", nullable = false) val eventType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") val payload: Map<String, Any?>,
    @Column(name = "correlation_id", nullable = false) val correlationId: UUID,
    @Column(name = "consumed_at", nullable = false) val consumedAt: Instant = Instant.now(),
    @Column(name = "processed_at") var processedAt: Instant? = null,
    @Column(name = "created_by", nullable = false) val createdBy: UUID,
) {
    fun markProcessed(at: Instant) {
        processedAt = at
    }
}