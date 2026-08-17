package com.trips_enjoy.trip.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inbox_event", schema = "trip")
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