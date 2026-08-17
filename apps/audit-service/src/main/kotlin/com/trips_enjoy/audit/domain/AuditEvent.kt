package com.trips_enjoy.audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Immutable, hash-chained audit row. Per docs/services/audit-service/ERD.md §3.
 *
 * The PK is composite `(id, created_at)` because the parent table is
 * range-partitioned on `created_at` — PostgreSQL requires the partition key
 * to participate in any UNIQUE constraint on a partitioned table.
 */
@Entity
@Table(name = "events", schema = "audit")
@IdClass(AuditEvent.Pk::class)
class AuditEvent(
    @Id
    val id: UUID,

    @Column(name = "event_id", nullable = false)
    val eventId: UUID,

    @Column(name = "event_name", nullable = false)
    val eventName: String,

    @Column(name = "schema_version", nullable = false)
    val schemaVersion: Int,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    @Column(nullable = false)
    val producer: String,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,

    @Column(name = "correlation_id", nullable = false)
    val correlationId: UUID,

    @Column(name = "causation_id")
    val causationId: UUID? = null,

    @Column(name = "aggregate_type", nullable = false)
    val aggregateType: String,

    @Column(name = "aggregate_id")
    val aggregateId: UUID? = null,

    @Column(name = "subject_type")
    val subjectType: String? = null,

    @Column(name = "subject_id")
    val subjectId: UUID? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val data: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val headers: String? = null,

    @Column(nullable = false)
    val topic: String,

    @Column(nullable = false)
    val partition: Int,

    @Column(name = "\"offset\"", nullable = false)
    val offset: Long,

    @Column(name = "prev_hash")
    val prevHash: String? = null,

    @Column(nullable = false)
    val hash: String,

    @Column(name = "retention_class", nullable = false)
    val retentionClass: String,

    @Column(name = "litigation_hold", nullable = false)
    val litigationHold: Boolean = false,

    @Column(name = "retention_until")
    val retentionUntil: Instant? = null,

    @Id
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    /** Composite PK; required by JPA because the parent table is partitioned. */
    data class Pk(val id: UUID, val createdAt: Instant) : Serializable {
        companion object {
            @Suppress("unused")
            @java.io.Serial
            private const val serialVersionUID: Long = 1L
        }

        /** No-arg constructor required by Hibernate 7's reflection-based PK instantiation. */
        @Suppress("unused")
        constructor() : this(UUID(0, 0), Instant.EPOCH)
    }
}
